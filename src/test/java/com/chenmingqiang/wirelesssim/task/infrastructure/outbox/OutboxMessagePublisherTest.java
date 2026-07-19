package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** 不连接Broker，精确验证单消息发布器的所有Confirm与Return分支。 */
class OutboxMessagePublisherTest {

    /** 验证ACK且没有Return时成功，并检查生成的持久化消息属性。 */
    @Test
    void returnsAckAndBuildsPersistentBusinessMessage() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        OutboxMessagePublisher publisher = newPublisher(rabbitTemplate, Duration.ofSeconds(1));
        OutboxEvent event = newEvent();
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);

        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                eq(SimulationRabbitNames.TASK_EXCHANGE),
                eq(SimulationRabbitNames.EXECUTE_ROUTING_KEY),
                messageCaptor.capture(),
                any(CorrelationData.class)
        );

        OutboxPublishResult result = publisher.publish(event);

        Message message = messageCaptor.getValue();
        assertThat(result.outcome()).isEqualTo(OutboxPublishOutcome.ACK);
        assertThat(result.isSuccess()).isTrue();
        assertThat(new String(message.getBody(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo(event.getPayloadJson());
        assertThat(message.getMessageProperties().getDeliveryMode())
                .isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(event.getEventId());
        assertThat(message.getMessageProperties().getPriority()).isEqualTo(event.getPriority());
        Integer attemptNo = message.getMessageProperties().getHeader("attemptNo");
        assertThat(attemptNo).isEqualTo(event.getAttemptNo());
    }

    /** 验证Broker ACK但消息不可路由时必须以Return失败，不能误判成功。 */
    @Test
    void returnsReturnedWhenMandatoryMessageCannotBeRouted() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        OutboxMessagePublisher publisher = newPublisher(rabbitTemplate, Duration.ofSeconds(1));
        OutboxEvent event = newEvent();

        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.setReturned(new ReturnedMessage(
                    message, 312, "NO_ROUTE", SimulationRabbitNames.TASK_EXCHANGE, "missing.route"
            ));
            correlationData.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(
                eq(SimulationRabbitNames.TASK_EXCHANGE),
                eq("missing.route"),
                any(Message.class),
                any(CorrelationData.class)
        );

        OutboxPublishResult result = publisher.publishTo(
                event, SimulationRabbitNames.TASK_EXCHANGE, "missing.route"
        );

        assertThat(result.outcome()).isEqualTo(OutboxPublishOutcome.RETURNED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.detail()).contains("312", "NO_ROUTE", "missing.route");
    }

    /** 验证Broker明确NACK时保留拒绝原因。 */
    @Test
    void returnsNackWithBrokerReason() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        OutboxMessagePublisher publisher = newPublisher(rabbitTemplate, Duration.ofSeconds(1));

        doAnswer(invocation -> {
            CorrelationData correlationData = invocation.getArgument(3);
            correlationData.getFuture().complete(new CorrelationData.Confirm(false, "broker rejected"));
            return null;
        }).when(rabbitTemplate).send(
                any(String.class), any(String.class), any(Message.class), any(CorrelationData.class)
        );

        OutboxPublishResult result = publisher.publish(newEvent());

        assertThat(result.outcome()).isEqualTo(OutboxPublishOutcome.NACK);
        assertThat(result.detail()).contains("broker rejected");
    }

    /** 验证Confirm Future一直不完成时按配置返回TIMEOUT。 */
    @Test
    void returnsTimeoutWhenConfirmDoesNotArrive() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        OutboxMessagePublisher publisher = newPublisher(rabbitTemplate, Duration.ofMillis(10));
        doNothing().when(rabbitTemplate).send(
                any(String.class), any(String.class), any(Message.class), any(CorrelationData.class)
        );

        OutboxPublishResult result = publisher.publish(newEvent());

        assertThat(result.outcome()).isEqualTo(OutboxPublishOutcome.TIMEOUT);
        assertThat(result.detail()).contains("PT0.01S");
    }

    /** 验证连接等同步发送异常被转换为可持久化处理的SEND_FAILED。 */
    @Test
    void returnsSendFailedWhenRabbitTemplateThrowsImmediately() {
        RabbitTemplate rabbitTemplate = mock(RabbitTemplate.class);
        OutboxMessagePublisher publisher = newPublisher(rabbitTemplate, Duration.ofSeconds(1));
        doThrow(new AmqpException("connection refused"))
                .when(rabbitTemplate).send(
                        any(String.class), any(String.class), any(Message.class), any(CorrelationData.class)
                );

        OutboxPublishResult result = publisher.publish(newEvent());

        assertThat(result.outcome()).isEqualTo(OutboxPublishOutcome.SEND_FAILED);
        assertThat(result.detail()).contains("connection refused");
    }

    /** 创建满足配置约束且使用指定Confirm超时的发布器。 */
    private OutboxMessagePublisher newPublisher(RabbitTemplate rabbitTemplate, Duration confirmTimeout) {
        OutboxPublisherProperties properties = new OutboxPublisherProperties(
                true,
                Duration.ofSeconds(1),
                1,
                Duration.ofSeconds(2),
                confirmTimeout,
                Duration.ofSeconds(5),
                Duration.ofMinutes(5)
        );
        return new OutboxMessagePublisher(rabbitTemplate, properties);
    }

    /** 创建字段完整的内存Outbox事件。 */
    private OutboxEvent newEvent() {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent();
        event.setId(501L);
        event.setEventId(eventId);
        event.setAggregateType("EXPERIMENT_TASK");
        event.setAggregateId(1001L);
        event.setEventType("TASK_EXECUTION_REQUESTED");
        event.setAttemptNo(1);
        event.setSchemaVersion(1);
        event.setPayloadJson("{\"eventId\":\"" + eventId + "\",\"taskId\":1001}");
        event.setPriority(3);
        event.setOccurredAt(LocalDateTime.now());
        return event;
    }
}
