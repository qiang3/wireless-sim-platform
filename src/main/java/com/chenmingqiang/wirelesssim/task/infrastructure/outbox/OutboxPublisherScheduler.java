package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

import com.chenmingqiang.wirelesssim.task.application.OutboxClaimService;
import com.chenmingqiang.wirelesssim.task.application.OutboxPublishResultService;
import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 定时驱动Outbox“领取、发送、结果落库”闭环。
 *
 * <p>该组件只在RabbitMQ分发模式且Outbox开关打开时创建。每个实例拥有唯一发布器ID，
 * 跨实例的领取和状态更新安全性由MySQL行锁及claimed_by条件共同保证。</p>
 */
@Component
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "dispatch-mode",
        havingValue = "rabbitmq"
)
@ConditionalOnProperty(
        prefix = "simulation.outbox",
        name = "enabled",
        havingValue = "true"
)
public class OutboxPublisherScheduler {

    /** 发布过程日志。 */
    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherScheduler.class);

    /** 负责在数据库短事务中领取事件和恢复超时租约。 */
    private final OutboxClaimService claimService;
    /** 负责发送单条持久化消息并判定Confirm/Return。 */
    private final OutboxMessagePublisher messagePublisher;
    /** 负责把发送结果在独立事务中写回MySQL。 */
    private final OutboxPublishResultService resultService;
    /** 当前应用实例的唯一发布器标识，生命周期内保持不变。 */
    private final String publisherId;

    /** 由Spring注入发布闭环所需的三个协作对象，并生成实例级发布器ID。 */
    @Autowired
    public OutboxPublisherScheduler(
            OutboxClaimService claimService,
            OutboxMessagePublisher messagePublisher,
            OutboxPublishResultService resultService
    ) {
        this(
                claimService,
                messagePublisher,
                resultService,
                "outbox-publisher-" + UUID.randomUUID()
        );
    }

    /** 包级构造方法允许单元测试传入固定发布器ID并验证所有权参数。 */
    OutboxPublisherScheduler(
            OutboxClaimService claimService,
            OutboxMessagePublisher messagePublisher,
            OutboxPublishResultService resultService,
            String publisherId
    ) {
        this.claimService = claimService;
        this.messagePublisher = messagePublisher;
        this.resultService = resultService;
        this.publisherId = publisherId;
    }

    /**
     * 每轮领取一批到期事件并逐条完成发送和结果落库。
     *
     * <p>fixedDelay从上一轮结束后开始计时，避免上一批等待Confirm较久时产生重叠执行。</p>
     */
    @Scheduled(
            fixedDelayString = "${simulation.outbox.scan-interval:1s}",
            initialDelayString = "${simulation.outbox.scan-interval:1s}"
    )
    public void publishOnce() {
        List<OutboxEvent> events;
        try {
            events = claimService.claimBatch(publisherId);
        } catch (RuntimeException exception) {
            log.error("Outbox批量领取失败，将等待下一轮扫描：publisherId={}", publisherId, exception);
            return;
        }

        for (OutboxEvent event : events) {
            publishOne(event);
        }
    }

    /** 定期恢复发布器宕机后超过租约的SENDING事件。 */
    @Scheduled(
            fixedDelayString = "${simulation.outbox.scan-interval:1s}",
            initialDelayString = "${simulation.outbox.lease-duration:2m}"
    )
    public void recoverExpiredOnce() {
        try {
            int recovered = claimService.recoverExpiredClaims();
            if (recovered > 0) {
                log.warn("已恢复Outbox超时租约：count={}", recovered);
            }
        } catch (RuntimeException exception) {
            log.error("恢复Outbox超时租约失败，将等待下一轮扫描", exception);
        }
    }

    /** 单条发送异常与结果落库异常彼此隔离，不阻断本批其他事件。 */
    private void publishOne(OutboxEvent event) {
        OutboxPublishResult result;
        try {
            result = messagePublisher.publish(event);
        } catch (RuntimeException exception) {
            result = OutboxPublishResult.failure(
                    OutboxPublishOutcome.SEND_FAILED,
                    exception.getClass().getSimpleName() + ": " + exception.getMessage()
            );
        }

        try {
            boolean updated = resultService.recordResult(event, publisherId, result);
            if (!updated) {
                log.warn(
                        "Outbox发布结果因所有权或状态变化未落库：eventId={}, outcome={}",
                        event.getEventId(),
                        result.outcome()
                );
            } else if (result.isSuccess()) {
                log.info("Outbox消息发布成功：eventId={}", event.getEventId());
            } else {
                log.warn(
                        "Outbox消息发布失败并已安排重试：eventId={}, outcome={}, detail={}",
                        event.getEventId(),
                        result.outcome(),
                        result.detail()
                );
            }
        } catch (RuntimeException exception) {
            // 留在SENDING，后续由租约恢复，不能在网络结果未知时直接删除或跳过事件。
            log.error(
                    "Outbox发布结果落库失败，事件将由租约恢复：eventId={}, outcome={}",
                    event.getEventId(),
                    result.outcome(),
                    exception
            );
        }
    }
}
