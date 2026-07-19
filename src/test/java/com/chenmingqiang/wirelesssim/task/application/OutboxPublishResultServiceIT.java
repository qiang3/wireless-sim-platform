package com.chenmingqiang.wirelesssim.task.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.domain.OutboxStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublishOutcome;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublishResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用真实MySQL验证发布成功、失败退避和领取者条件更新。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.outbox.batch-size=1"
})
class OutboxPublishResultServiceIT {

    /** 领取真实测试事件。 */
    @Autowired
    private OutboxClaimService claimService;
    /** 被测试的发布结果事务服务。 */
    @Autowired
    private OutboxPublishResultService resultService;
    /** 插入并查询测试事件。 */
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    /** 调整测试排序并定向清理测试数据。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 本测试创建的全局事件ID。 */
    private final List<String> createdEventIds = new ArrayList<>();

    /** 删除本测试创建的数据。 */
    @AfterEach
    void cleanUp() {
        for (String eventId : createdEventIds) {
            jdbcTemplate.update("DELETE FROM outbox_event WHERE event_id = ?", eventId);
        }
    }

    /** ACK后标记PUBLISHED并清空领取信息。 */
    @Test
    void marksOwnedSendingEventPublishedAfterAck() {
        OutboxEvent inserted = insertReadyEvent(94001L);
        OutboxEvent claimed = claimService.claimBatch("result-it-ack").get(0);

        boolean updated = resultService.recordResult(claimed, "result-it-ack", OutboxPublishResult.ack());

        OutboxEvent saved = outboxEventMapper.findByEventId(inserted.getEventId());
        assertThat(updated).isTrue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(saved.getPublishedAt()).isNotNull();
        assertThat(saved.getClaimedBy()).isNull();
        assertThat(saved.getClaimedAt()).isNull();
        assertThat(saved.getLastError()).isNull();
    }

    /** 第一次失败后恢复PENDING，并把下次发送安排在约5秒后。 */
    @Test
    void reschedulesOwnedEventWithExponentialBackoffAfterFailure() {
        OutboxEvent inserted = insertReadyEvent(94002L);
        OutboxEvent claimed = claimService.claimBatch("result-it-failure").get(0);
        LocalDateTime beforeUpdate = databaseNow();

        boolean updated = resultService.recordResult(
                claimed,
                "result-it-failure",
                OutboxPublishResult.failure(OutboxPublishOutcome.TIMEOUT, "confirm timeout")
        );

        LocalDateTime afterUpdate = databaseNow();
        OutboxEvent saved = outboxEventMapper.findByEventId(inserted.getEventId());
        assertThat(updated).isTrue();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getNextAttemptAt())
                .isBetween(beforeUpdate.plusSeconds(5), afterUpdate.plusSeconds(6));
        assertThat(saved.getLastError()).contains("TIMEOUT", "confirm timeout");
        assertThat(saved.getClaimedBy()).isNull();
        assertThat(saved.getPublishedAt()).isNull();
    }

    /** 错误领取者不能写入ACK，事件保持原发布器拥有的SENDING状态。 */
    @Test
    void rejectsLateResultFromPublisherThatDoesNotOwnEvent() {
        OutboxEvent inserted = insertReadyEvent(94003L);
        OutboxEvent claimed = claimService.claimBatch("result-it-owner").get(0);

        boolean updated = resultService.recordResult(claimed, "result-it-stale", OutboxPublishResult.ack());

        OutboxEvent saved = outboxEventMapper.findByEventId(inserted.getEventId());
        assertThat(updated).isFalse();
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.SENDING);
        assertThat(saved.getClaimedBy()).isEqualTo("result-it-owner");
        assertThat(saved.getPublishedAt()).isNull();
    }

    /** 返回MySQL当前时间，确保退避断言不依赖应用与数据库时钟完全一致。 */
    private LocalDateTime databaseNow() {
        return jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
    }

    /** 插入一条在测试环境中优先被领取的事件。 */
    private OutboxEvent insertReadyEvent(Long taskId) {
        String eventId = UUID.randomUUID().toString();
        createdEventIds.add(eventId);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType("EXPERIMENT_TASK");
        event.setAggregateId(taskId);
        event.setEventType("TASK_EXECUTION_REQUESTED");
        event.setAttemptNo(1);
        event.setSchemaVersion(1);
        event.setPayloadJson("{\"eventId\":\"" + eventId + "\",\"taskId\":" + taskId + "}");
        event.setPriority(3);
        event.setOccurredAt(LocalDateTime.now());
        assertThat(outboxEventMapper.insertPending(event)).isEqualTo(1);
        jdbcTemplate.update(
                "UPDATE outbox_event SET next_attempt_at='1000-01-01 00:00:00.000', "
                        + "created_at='1000-01-01 00:00:00.000' WHERE id=?",
                event.getId()
        );
        return event;
    }
}
