package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.domain.OutboxStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublisherScheduler;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 使用真实MySQL和RabbitMQ验证“领取、发送、Confirm、PUBLISHED落库”完整闭环。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.outbox.enabled=true",
        "simulation.outbox.scan-interval=1h",
        "simulation.outbox.batch-size=1"
})
class OutboxPublishingFlowIT {

    /** 手动触发完整发布轮次，自动调度因1小时初始延迟不会干扰测试。 */
    @Autowired
    private OutboxPublisherScheduler scheduler;
    /** 插入并查询真实Outbox事件。 */
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    /** 清理及调整测试事件顺序。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 读取和清理RabbitMQ主队列。 */
    @Autowired
    private RabbitTemplate rabbitTemplate;
    /** 按JSON结构而非键顺序比较消息正文。 */
    @Autowired
    private ObjectMapper objectMapper;
    /** 当前测试创建的事件ID。 */
    private String createdEventId;

    /** 测试前清空主执行队列。 */
    @BeforeEach
    void purgeQueue() {
        rabbitTemplate.execute(channel -> channel.queuePurge(SimulationRabbitNames.EXECUTE_QUEUE));
    }

    /** 测试后定向删除Outbox事件并清空队列。 */
    @AfterEach
    void cleanUp() {
        if (createdEventId != null) {
            jdbcTemplate.update("DELETE FROM outbox_event WHERE event_id=?", createdEventId);
        }
        rabbitTemplate.execute(channel -> channel.queuePurge(SimulationRabbitNames.EXECUTE_QUEUE));
    }

    /** 验证一次手动扫描把数据库事件可靠发送并最终标记PUBLISHED。 */
    @Test
    void closesRealDatabaseAndRabbitPublishLoop() throws Exception {
        OutboxEvent event = insertReadyEvent();

        scheduler.publishOnce();

        OutboxEvent saved = outboxEventMapper.findByEventId(event.getEventId());
        Message received = rabbitTemplate.receive(SimulationRabbitNames.EXECUTE_QUEUE, 2000);
        assertThat(saved.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(saved.getPublishAttempts()).isEqualTo(1);
        assertThat(saved.getPublishedAt()).isNotNull();
        assertThat(saved.getClaimedBy()).isNull();
        assertThat(received).isNotNull();
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(event.getEventId());
        assertThat(objectMapper.readTree(new String(received.getBody(), StandardCharsets.UTF_8)))
                .isEqualTo(objectMapper.readTree(event.getPayloadJson()));
    }

    /** 插入一条在开发库已有事件之前被测试轮次领取的记录。 */
    private OutboxEvent insertReadyEvent() {
        createdEventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent();
        event.setEventId(createdEventId);
        event.setAggregateType("EXPERIMENT_TASK");
        event.setAggregateId(95001L);
        event.setEventType("TASK_EXECUTION_REQUESTED");
        event.setAttemptNo(1);
        event.setSchemaVersion(1);
        event.setPayloadJson("{\"eventId\":\"" + createdEventId + "\",\"taskId\":95001}");
        event.setPriority(5);
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
