package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxMessagePublisher;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublishOutcome;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublishResult;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 使用真实RabbitMQ验证单条Outbox消息可靠进入主执行队列。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.outbox.enabled=false"
})
class OutboxMessagePublisherIT {

    /** 被测试的单消息发布器。 */
    @Autowired
    private OutboxMessagePublisher publisher;
    /** 用于清理并读取真实RabbitMQ主队列。 */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 每项测试前清空主队列，避免人工调试消息影响断言。 */
    @BeforeEach
    void purgeBeforeTest() {
        rabbitTemplate.execute(channel -> channel.queuePurge(SimulationRabbitNames.EXECUTE_QUEUE));
    }

    /** 每项测试后再次清空测试消息，不污染后续消费者调试。 */
    @AfterEach
    void purgeAfterTest() {
        rabbitTemplate.execute(channel -> channel.queuePurge(SimulationRabbitNames.EXECUTE_QUEUE));
    }

    /** 验证真实Broker Confirm成功，且队列中的消息正文和关键属性完整。 */
    @Test
    void publishesPersistentJsonMessageToRealExecuteQueue() {
        OutboxEvent event = newEvent();

        OutboxPublishResult result = publisher.publish(event);
        Message received = rabbitTemplate.receive(SimulationRabbitNames.EXECUTE_QUEUE, 2000);

        assertThat(result.outcome()).isEqualTo(OutboxPublishOutcome.ACK);
        assertThat(received).isNotNull();
        assertThat(new String(received.getBody(), StandardCharsets.UTF_8))
                .isEqualTo(event.getPayloadJson());
        assertThat(received.getMessageProperties().getMessageId()).isEqualTo(event.getEventId());
        assertThat(received.getMessageProperties().getReceivedDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(received.getMessageProperties().getPriority()).isEqualTo(event.getPriority());
        Integer attemptNo = received.getMessageProperties().getHeader("attemptNo");
        assertThat(attemptNo).isEqualTo(event.getAttemptNo());
    }

    /** 创建一条只用于真实RabbitMQ发送、不写数据库的完整事件。 */
    private OutboxEvent newEvent() {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent();
        event.setId(601L);
        event.setEventId(eventId);
        event.setAggregateType("EXPERIMENT_TASK");
        event.setAggregateId(2001L);
        event.setEventType("TASK_EXECUTION_REQUESTED");
        event.setAttemptNo(1);
        event.setSchemaVersion(1);
        event.setPayloadJson("{\"eventId\":\"" + eventId + "\",\"taskId\":2001}");
        event.setPriority(4);
        event.setOccurredAt(LocalDateTime.now());
        return event;
    }
}
