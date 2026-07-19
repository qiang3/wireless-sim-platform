package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.domain.OutboxStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxMessagePublisher;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublishOutcome;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublishResult;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublisherScheduler;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 使用真实MySQL和调度器验证发布失败后的持久化退避闭环。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.outbox.enabled=true",
        "simulation.outbox.scan-interval=1h",
        "simulation.outbox.batch-size=1"
})
class OutboxPublishingFailureIT {

    /** 手动触发发布轮次。 */
    @Autowired
    private OutboxPublisherScheduler scheduler;
    /** 插入并查询真实Outbox记录。 */
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    /** 调整测试事件顺序并清理数据。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 只替换网络发布器，领取、结果事务、Mapper和MySQL保持真实。 */
    @MockitoBean
    private OutboxMessagePublisher messagePublisher;
    /** 本测试类创建的事件ID。 */
    private final List<String> createdEventIds = new ArrayList<>();

    /** 每项测试后删除自身事件。 */
    @AfterEach
    void cleanUp() {
        for (String eventId : createdEventIds) {
            jdbcTemplate.update("DELETE FROM outbox_event WHERE event_id=?", eventId);
        }
    }

    /** Confirm超时后恢复PENDING，并且立即下一轮不会越过5秒退避再次发送。 */
    @Test
    void timeoutIsPersistedAndNotImmediatelyRepublished() {
        OutboxEvent event = insertReadyEvent(96001L);
        when(messagePublisher.publish(any())).thenReturn(
                OutboxPublishResult.failure(OutboxPublishOutcome.TIMEOUT, "confirm timeout")
        );

        scheduler.publishOnce();

        OutboxEvent saved = outboxEventMapper.findByEventId(event.getEventId());
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getPublishAttempts()).isEqualTo(1);
        assertThat(saved.getNextAttemptAt()).isAfter(databaseNow());
        assertThat(saved.getLastError()).contains("TIMEOUT", "confirm timeout");
        verify(messagePublisher, times(1)).publish(argThat(
                published -> published.getEventId().equals(event.getEventId())
        ));
    }

    /** 模拟Broker连接异常，验证SEND_FAILED同样进入可恢复退避。 */
    @Test
    void brokerUnavailableIsPersistedForRetry() {
        OutboxEvent event = insertReadyEvent(96002L);
        when(messagePublisher.publish(any())).thenReturn(
                OutboxPublishResult.failure(OutboxPublishOutcome.SEND_FAILED, "connection refused")
        );

        scheduler.publishOnce();

        OutboxEvent saved = outboxEventMapper.findByEventId(event.getEventId());
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getNextAttemptAt()).isAfter(databaseNow());
        assertThat(saved.getLastError()).contains("SEND_FAILED", "connection refused");
    }

    /** 查询数据库当前时间，避免测试依赖应用与MySQL时钟完全一致。 */
    private LocalDateTime databaseNow() {
        return jdbcTemplate.queryForObject("SELECT CURRENT_TIMESTAMP(3)", LocalDateTime.class);
    }

    /** 插入在开发数据之前被领取的测试事件。 */
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
