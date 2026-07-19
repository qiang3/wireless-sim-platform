package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chenmingqiang.wirelesssim.task.application.TaskMessageFailureService;
import com.chenmingqiang.wirelesssim.task.application.TaskMessagePreparationOutcome;
import com.chenmingqiang.wirelesssim.task.application.TaskMessagePreparationResult;
import com.chenmingqiang.wirelesssim.task.application.TaskMessagePreparationService;
import com.chenmingqiang.wirelesssim.task.infrastructure.execution.SimulationTaskWorker;
import com.rabbitmq.client.Channel;
import java.time.Instant;
import java.time.Duration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** 不依赖Broker，精确验证每种消费结论对应的ACK、Reject或NACK。 */
class TaskExecutionMessageListenerTest {

    private TaskExecutionMessageValidator validator;
    private TaskMessagePreparationService preparationService;
    private SimulationTaskWorker worker;
    private Channel channel;
    private TaskMessageDeliveryAttemptResolver attemptResolver;
    private TaskMessageForwarder forwarder;
    private TaskMessageFailureService failureService;
    private TaskExecutionMessageListener listener;
    private Message rawMessage;
    private TaskExecutionRequestedMessage validMessage;

    @BeforeEach
    void setUp() {
        validator = mock(TaskExecutionMessageValidator.class);
        preparationService = mock(TaskMessagePreparationService.class);
        worker = mock(SimulationTaskWorker.class);
        channel = mock(Channel.class);
        attemptResolver = mock(TaskMessageDeliveryAttemptResolver.class);
        forwarder = mock(TaskMessageForwarder.class);
        failureService = mock(TaskMessageFailureService.class);
        SimulationMessagingProperties messagingProperties = new SimulationMessagingProperties(
                Duration.ofSeconds(10), 3, 5, Duration.ofSeconds(1));
        listener = new TaskExecutionMessageListener(
                validator,
                preparationService,
                worker,
                attemptResolver,
                forwarder,
                failureService,
                messagingProperties
        );

        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(91L);
        rawMessage = new Message(new byte[]{1}, properties);
        validMessage = new TaskExecutionRequestedMessage(
                "event-1", 101L, 1, "TASK_EXECUTION_REQUESTED", 1, Instant.now()
        );
        when(validator.parseAndValidate(rawMessage)).thenReturn(validMessage);
        when(attemptResolver.resolve(rawMessage)).thenReturn(1);
    }

    @Test
    void readyMessageRunsWorkerThenAcksEvenWhenBusinessExecutionReturnsFalse() throws Exception {
        when(preparationService.prepare(101L, 1)).thenReturn(result(TaskMessagePreparationOutcome.READY_TO_EXECUTE));
        when(worker.execute(101L, 1)).thenReturn(false);

        listener.onMessage(rawMessage, channel);

        verify(worker).execute(101L, 1);
        verify(channel).basicAck(91L, false);
    }

    @Test
    void duplicateOrStaleMessageAcksWithoutWorker() throws Exception {
        when(preparationService.prepare(101L, 1)).thenReturn(result(TaskMessagePreparationOutcome.STALE_ATTEMPT));

        listener.onMessage(rawMessage, channel);

        verify(channel).basicAck(91L, false);
        verify(worker, org.mockito.Mockito.never()).execute(any(Long.class), any(Integer.class));
    }

    @Test
    void futureAttemptIsPublishedToDeadLetterThenAcked() throws Exception {
        when(preparationService.prepare(101L, 1)).thenReturn(result(TaskMessagePreparationOutcome.FUTURE_ATTEMPT));
        when(forwarder.forwardToDeadLetter(rawMessage, 1, "test"))
                .thenReturn(TaskMessageForwardResult.ack());

        listener.onMessage(rawMessage, channel);

        verify(forwarder).forwardToDeadLetter(rawMessage, 1, "test");
        verify(channel).basicAck(91L, false);
    }

    @Test
    void invalidContractIsPublishedToDeadLetterThenAcked() throws Exception {
        when(validator.parseAndValidate(rawMessage)).thenThrow(new InvalidTaskMessageException("非法版本"));
        when(forwarder.forwardToDeadLetter(rawMessage, 1, "非法版本"))
                .thenReturn(TaskMessageForwardResult.ack());

        listener.onMessage(rawMessage, channel);

        verify(channel).basicAck(91L, false);
    }

    @Test
    void transientFailureIsForwardedToTtlRetryThenOriginalIsAcked() throws Exception {
        when(preparationService.prepare(101L, 1)).thenThrow(new IllegalStateException("数据库暂时不可用"));
        when(forwarder.forwardToRetry(
                rawMessage, 2, "IllegalStateException: 数据库暂时不可用"))
                .thenReturn(TaskMessageForwardResult.ack());

        listener.onMessage(rawMessage, channel);

        verify(forwarder).forwardToRetry(
                rawMessage, 2, "IllegalStateException: 数据库暂时不可用");
        verify(channel).basicAck(91L, false);
    }

    @Test
    void exhaustedTransientFailureGoesDeadMarksTaskAndAcks() throws Exception {
        when(attemptResolver.resolve(rawMessage)).thenReturn(3);
        when(preparationService.prepare(101L, 1)).thenThrow(new IllegalStateException("数据库仍不可用"));
        when(forwarder.forwardToDeadLetter(
                rawMessage, 3, "IllegalStateException: 数据库仍不可用"))
                .thenReturn(TaskMessageForwardResult.ack());
        when(failureService.markDeliveryExhausted(
                101L, 1, "IllegalStateException: 数据库仍不可用"))
                .thenReturn(true);

        listener.onMessage(rawMessage, channel);

        verify(failureService).markDeliveryExhausted(
                101L, 1, "IllegalStateException: 数据库仍不可用");
        verify(channel).basicAck(91L, false);
    }

    @Test
    void failedRetryForwardKeepsOriginalByNackRequeue() throws Exception {
        when(preparationService.prepare(101L, 1)).thenThrow(new IllegalStateException("连接失败"));
        when(forwarder.forwardToRetry(rawMessage, 2, "IllegalStateException: 连接失败"))
                .thenReturn(TaskMessageForwardResult.failure(
                        TaskMessageForwardOutcome.SEND_FAILED, "connection refused"));

        listener.onMessage(rawMessage, channel);

        verify(channel).basicNack(91L, false, true);
    }

    private TaskMessagePreparationResult result(TaskMessagePreparationOutcome outcome) {
        return new TaskMessagePreparationResult(outcome, "test");
    }
}
