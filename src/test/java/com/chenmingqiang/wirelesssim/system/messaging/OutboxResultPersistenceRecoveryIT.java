package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.chenmingqiang.wirelesssim.task.application.OutboxPublishResultService;
import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.domain.OutboxStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxMessagePublisher;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublishResult;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublisherScheduler;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 验证消息已发送但结果写库失败时，事件最终可由租约恢复。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.outbox.enabled=true",
        "simulation.outbox.scan-interval=1h",
        "simulation.outbox.batch-size=1"
})
class OutboxResultPersistenceRecoveryIT {

    /** 手动触发发布和租约恢复。 */
    @Autowired
    private OutboxPublisherScheduler scheduler;
    /** 插入并查询真实Outbox记录。 */
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    /** 修改租约时间并清理测试数据。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 模拟Broker已经ACK，避免测试真正发送消息。 */
    @MockitoBean
    private OutboxMessagePublisher messagePublisher;
    /** 故障注入：模拟Confirm后数据库更新失败。 */
    @MockitoBean
    private OutboxPublishResultService resultService;
    /** 当前测试事件ID。 */
    private String createdEventId;

    /** 测试结束后删除自身事件。 */
    @AfterEach
    void cleanUp() {
        if (createdEventId != null) {
            jdbcTemplate.update("DELETE FROM outbox_event WHERE event_id=?", createdEventId);
        }
    }

    /** 结果落库失败先保留SENDING，租约过期后恢复PENDING等待安全重发。 */
    @Test
    void recoversSendingEventAfterResultPersistenceFailure() {
        OutboxEvent event = insertReadyEvent();
        when(messagePublisher.publish(any())).thenReturn(OutboxPublishResult.ack());
        when(resultService.recordResult(any(), anyString(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        scheduler.publishOnce();
        OutboxEvent sending = outboxEventMapper.findByEventId(event.getEventId());
        assertThat(sending.getStatus()).isEqualTo(OutboxStatus.SENDING);
        assertThat(sending.getClaimedBy()).isNotBlank();

        jdbcTemplate.update(
                "UPDATE outbox_event SET claimed_at=DATE_SUB(CURRENT_TIMESTAMP(3), INTERVAL 10 MINUTE) WHERE id=?",
                event.getId()
        );
        scheduler.recoverExpiredOnce();

        OutboxEvent recovered = outboxEventMapper.findByEventId(event.getEventId());
        assertThat(recovered.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(recovered.getClaimedBy()).isNull();
        assertThat(recovered.getLastError()).contains("租约超时");
    }

    /** 插入一条优先被领取的测试事件。 */
    private OutboxEvent insertReadyEvent() {
        createdEventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent();
        event.setEventId(createdEventId);
        event.setAggregateType("EXPERIMENT_TASK");
        event.setAggregateId(97001L);
        event.setEventType("TASK_EXECUTION_REQUESTED");
        event.setAttemptNo(1);
        event.setSchemaVersion(1);
        event.setPayloadJson("{\"eventId\":\"" + createdEventId + "\",\"taskId\":97001}");
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
