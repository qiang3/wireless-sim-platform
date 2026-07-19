package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

/** 精确验证重试/死信转发属性以及Confirm、Return和发送失败分支。 */
class TaskMessageForwarderTest {

    @Test
    void retryForwardCopiesBusinessMessageAndAddsFailureHeaders() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        TaskMessageForwarder forwarder = newForwarder(template);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(
                eq(SimulationRabbitNames.RETRY_EXCHANGE),
                eq(SimulationRabbitNames.RETRY_ROUTING_KEY),
                captor.capture(),
                any(CorrelationData.class)
        );

        TaskMessageForwardResult result = forwarder.forwardToRetry(sourceMessage(), 2, "database timeout");

        Message forwarded = captor.getValue();
        assertThat(result.isSuccess()).isTrue();
        assertThat(forwarded.getBody()).isEqualTo(sourceMessage().getBody());
        assertThat(forwarded.getMessageProperties().getMessageId()).isEqualTo("event-1");
        assertThat(forwarded.getMessageProperties().getDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat((Integer) forwarded.getMessageProperties().getHeader("attemptNo")).isEqualTo(1);
        assertThat((Integer) forwarded.getMessageProperties().getHeader("x-delivery-attempt")).isEqualTo(2);
        assertThat((String) forwarded.getMessageProperties().getHeader("x-last-error"))
                .isEqualTo("database timeout");
        assertThat((Boolean) forwarded.getMessageProperties().getHeader("x-final-failure")).isFalse();
    }

    @Test
    void deadLetterSetsFinalFailureHeader() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        TaskMessageForwarder forwarder = newForwarder(template);
        ArgumentCaptor<Message> captor = ArgumentCaptor.forClass(Message.class);
        doAnswer(invocation -> {
            CorrelationData correlation = invocation.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(
                eq(SimulationRabbitNames.DEAD_LETTER_EXCHANGE),
                eq(SimulationRabbitNames.DEAD_LETTER_ROUTING_KEY),
                captor.capture(),
                any(CorrelationData.class)
        );

        assertThat(forwarder.forwardToDeadLetter(sourceMessage(), 3, "exhausted").isSuccess()).isTrue();
        assertThat((Integer) captor.getValue().getMessageProperties()
                .getHeader("x-delivery-attempt")).isEqualTo(3);
        assertThat((Boolean) captor.getValue().getMessageProperties()
                .getHeader("x-final-failure")).isTrue();
    }

    @Test
    void mandatoryReturnIsNotSuccess() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        TaskMessageForwarder forwarder = newForwarder(template);
        doAnswer(invocation -> {
            Message message = invocation.getArgument(2);
            CorrelationData correlation = invocation.getArgument(3);
            correlation.setReturned(new ReturnedMessage(
                    message, 312, "NO_ROUTE", "exchange", "route"));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(template).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        TaskMessageForwardResult result = forwarder.forwardToRetry(sourceMessage(), 2, "error");

        assertThat(result.outcome()).isEqualTo(TaskMessageForwardOutcome.RETURNED);
    }

    @Test
    void immediateSendFailureIsStructured() {
        RabbitTemplate template = mock(RabbitTemplate.class);
        TaskMessageForwarder forwarder = newForwarder(template);
        doThrow(new AmqpException("connection refused"))
                .when(template).send(any(String.class), any(String.class), any(Message.class), any(CorrelationData.class));

        TaskMessageForwardResult result = forwarder.forwardToDeadLetter(sourceMessage(), 1, "invalid");

        assertThat(result.outcome()).isEqualTo(TaskMessageForwardOutcome.SEND_FAILED);
        assertThat(result.detail()).contains("connection refused");
    }

    private TaskMessageForwarder newForwarder(RabbitTemplate template) {
        return new TaskMessageForwarder(template, new SimulationMessagingProperties(
                Duration.ofSeconds(10), 3, 5, Duration.ofSeconds(1)));
    }

    private Message sourceMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId("event-1");
        properties.setCorrelationId("event-1");
        properties.setType("TASK_EXECUTION_REQUESTED");
        properties.setPriority(3);
        properties.setHeader("attemptNo", 1);
        properties.setHeader("schemaVersion", 1);
        return new Message("{\"taskId\":101}".getBytes(StandardCharsets.UTF_8), properties);
    }
}
