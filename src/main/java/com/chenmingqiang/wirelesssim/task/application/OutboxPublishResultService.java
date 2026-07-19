package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublishResult;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxRetryBackoffCalculator;
import java.time.Duration;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在独立短事务中把RabbitMQ发布结果写回Outbox。
 *
 * <p>数据库更新始终校验领取者，防止旧发布器的迟到结果覆盖新发布器。</p>
 */
@Service
public class OutboxPublishResultService {

    /** V3迁移中last_error字段的最大字符数。 */
    private static final int MAX_ERROR_LENGTH = 1000;

    /** 执行带所有权条件的Outbox状态更新。 */
    private final OutboxEventMapper outboxEventMapper;
    /** 根据发布次数计算失败后的下一次延迟。 */
    private final OutboxRetryBackoffCalculator backoffCalculator;

    /** 由Spring构造器注入数据访问代理和退避计算器。 */
    public OutboxPublishResultService(
            OutboxEventMapper outboxEventMapper,
            OutboxRetryBackoffCalculator backoffCalculator
    ) {
        this.outboxEventMapper = outboxEventMapper;
        this.backoffCalculator = backoffCalculator;
    }

    /**
     * 记录一条消息的发布结果。
     *
     * @return true表示当前发布器成功更新一行；false表示所有权已失效或状态已变化
     */
    @Transactional
    public boolean recordResult(
            OutboxEvent event,
            String publisherId,
            OutboxPublishResult result
    ) {
        validate(event, publisherId, result);
        if (result.isSuccess()) {
            return outboxEventMapper.markPublished(event.getId(), publisherId) == 1;
        }

        Duration delay = backoffCalculator.calculate(event.getPublishAttempts());
        String errorMessage = truncate(result.outcome() + ": " + result.detail());
        return outboxEventMapper.rescheduleAfterFailure(
                event.getId(),
                publisherId,
                delay.toMillis(),
                errorMessage
        ) == 1;
    }

    /** 对来自领取阶段的数据和发布结果进行边界校验。 */
    private void validate(OutboxEvent event, String publisherId, OutboxPublishResult result) {
        if (event == null || event.getId() == null) {
            throw new IllegalArgumentException("待更新Outbox事件及其主键不能为空");
        }
        if (event.getPublishAttempts() == null || event.getPublishAttempts() < 1) {
            throw new IllegalArgumentException("Outbox发布尝试次数必须大于等于1");
        }
        if (publisherId == null || publisherId.isBlank()) {
            throw new IllegalArgumentException("发布器实例标识不能为空");
        }
        if (result == null || result.outcome() == null) {
            throw new IllegalArgumentException("发布结果不能为空");
        }
    }

    /** 截断错误摘要以适配VARCHAR(1000)，避免记录原始异常时再次写库失败。 */
    private String truncate(String message) {
        String safeMessage = message == null ? "未知发布错误" : message;
        return safeMessage.length() <= MAX_ERROR_LENGTH
                ? safeMessage
                : safeMessage.substring(0, MAX_ERROR_LENGTH);
    }
}
