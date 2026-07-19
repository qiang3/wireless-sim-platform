package com.chenmingqiang.wirelesssim.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.domain.OutboxStatus;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用真实MySQL验证OutboxEvent与MyBatis XML之间的完整持久化映射。 */
@SpringBootTest(properties = "simulation.execution.enabled=false")
class OutboxEventMapperIT {

    /** 被测试的MyBatis代理对象。 */
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    /** 只用于测试结束后删除本测试创建的数据。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 记录本测试创建的事件ID，保证每项测试互不污染。 */
    private final List<String> createdEventIds = new ArrayList<>();

    /** 每项测试后按全局事件ID清理Outbox记录。 */
    @AfterEach
    void cleanUp() {
        for (String eventId : createdEventIds) {
            jdbcTemplate.update("DELETE FROM outbox_event WHERE event_id = ?", eventId);
        }
    }

    /** 验证插入、数据库默认值、自增主键及全部主要字段都能正确映射回来。 */
    @Test
    void insertsPendingEventAndReadsDatabaseDefaults() {
        OutboxEvent event = newEvent(1001L, 1);

        assertThat(outboxEventMapper.insertPending(event)).isEqualTo(1);
        assertThat(event.getId()).isNotNull();

        OutboxEvent saved = outboxEventMapper.findByEventId(event.getEventId());
        assertThat(saved).isNotNull();
        assertThat(saved.getAggregateType()).isEqualTo("EXPERIMENT_TASK");
        assertThat(saved.getAggregateId()).isEqualTo(1001L);
        assertThat(saved.getEventType()).isEqualTo("TASK_EXECUTION_REQUESTED");
        assertThat(saved.getAttemptNo()).isEqualTo(1);
        assertThat(saved.getSchemaVersion()).isEqualTo(1);
        assertThat(saved.getPayloadJson()).contains("\"taskId\": 1001");
        assertThat(saved.getPriority()).isEqualTo(3);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PENDING);
        assertThat(saved.getPublishAttempts()).isZero();
        assertThat(saved.getNextAttemptAt()).isNotNull();
        assertThat(saved.getClaimedBy()).isNull();
        assertThat(saved.getClaimedAt()).isNull();
        assertThat(saved.getLastError()).isNull();
        assertThat(saved.getPublishedAt()).isNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        OutboxEvent byBusinessKey = outboxEventMapper.findByBusinessKey(
                "EXPERIMENT_TASK", 1001L, "TASK_EXECUTION_REQUESTED", 1);
        assertThat(byBusinessKey.getEventId()).isEqualTo(event.getEventId());
    }

    /** 验证不同UUID也不能绕过同一任务尝试的业务唯一约束。 */
    @Test
    void rejectsDuplicateBusinessEventWithDifferentEventId() {
        OutboxEvent first = newEvent(1002L, 1);
        OutboxEvent duplicate = newEvent(1002L, 1);

        assertThat(outboxEventMapper.insertPending(first)).isEqualTo(1);
        assertThatThrownBy(() -> outboxEventMapper.insertPending(duplicate))
                .isInstanceOf(DuplicateKeyException.class);
    }

    /** 创建字段完整且全局ID唯一的待发布测试事件。 */
    private OutboxEvent newEvent(Long taskId, int attemptNo) {
        String eventId = UUID.randomUUID().toString();
        createdEventIds.add(eventId);

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType("EXPERIMENT_TASK");
        event.setAggregateId(taskId);
        event.setEventType("TASK_EXECUTION_REQUESTED");
        event.setAttemptNo(attemptNo);
        event.setSchemaVersion(1);
        event.setPayloadJson("{\"eventId\": \"" + eventId + "\", \"taskId\": " + taskId + "}");
        event.setPriority(3);
        event.setOccurredAt(LocalDateTime.now());
        return event;
    }
}
