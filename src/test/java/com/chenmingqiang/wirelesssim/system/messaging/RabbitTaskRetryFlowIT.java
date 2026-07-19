package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.chenmingqiang.wirelesssim.task.application.TaskMessagePreparationService;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.TaskExecutionRequestedMessage;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;

/** 使用真实监听容器和RabbitMQ验证临时异常的有限重试与最终死信分流。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=true",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.messaging.consumer-enabled=true",
        "simulation.outbox.enabled=false",
        "simulation.execution.recovery-scan-interval-ms=3600000"
})
class RabbitTaskRetryFlowIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private ObjectMapper objectMapper;

    /** 主动制造尚未进入Worker的临时数据库异常。 */
    @MockitoBean
    private TaskMessagePreparationService preparationService;

    @BeforeEach
    void setUp() {
        purgeQueues();
        when(preparationService.prepare(anyLong(), anyInt()))
                .thenThrow(new IllegalStateException("temporary database failure"));
    }

    @AfterEach
    void cleanUp() {
        purgeQueues();
    }

    @Test
    void firstTemporaryFailureMovesMessageToRetryQueueAndAcksOriginal() throws Exception {
        publish(message(1));

        Message retry = rabbitTemplate.receive(SimulationRabbitNames.RETRY_QUEUE, 5000);

        assertThat(retry).isNotNull();
        assertThat((Integer) retry.getMessageProperties().getHeader("x-delivery-attempt")).isEqualTo(2);
        assertThat((Boolean) retry.getMessageProperties().getHeader("x-final-failure")).isFalse();
        assertThat(queueCount(SimulationRabbitNames.EXECUTE_QUEUE)).isZero();
    }

    @Test
    void thirdTemporaryFailureMovesMessageToFinalDeadQueue() throws Exception {
        publish(message(3));

        Message dead = rabbitTemplate.receive(SimulationRabbitNames.DEAD_LETTER_QUEUE, 5000);

        assertThat(dead).isNotNull();
        assertThat((Integer) dead.getMessageProperties().getHeader("x-delivery-attempt")).isEqualTo(3);
        assertThat((Boolean) dead.getMessageProperties().getHeader("x-final-failure")).isTrue();
        assertThat(queueCount(SimulationRabbitNames.RETRY_QUEUE)).isZero();
    }

    private void publish(Message message) {
        rabbitTemplate.send(
                SimulationRabbitNames.TASK_EXCHANGE,
                SimulationRabbitNames.EXECUTE_ROUTING_KEY,
                message
        );
    }

    private Message message(int deliveryAttempt) throws Exception {
        String eventId = UUID.randomUUID().toString();
        TaskExecutionRequestedMessage payload = new TaskExecutionRequestedMessage(
                eventId, 999999L, 1, "TASK_EXECUTION_REQUESTED", 1, Instant.now());
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(eventId);
        properties.setCorrelationId(eventId);
        properties.setType("TASK_EXECUTION_REQUESTED");
        properties.setPriority(3);
        properties.setHeader("attemptNo", 1);
        properties.setHeader("schemaVersion", 1);
        properties.setHeader("x-delivery-attempt", deliveryAttempt);
        return new Message(objectMapper.writeValueAsBytes(payload), properties);
    }

    private long queueCount(String queue) {
        return rabbitTemplate.execute(channel -> channel.messageCount(queue));
    }

    private void purgeQueues() {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(SimulationRabbitNames.EXECUTE_QUEUE);
            channel.queuePurge(SimulationRabbitNames.RETRY_QUEUE);
            channel.queuePurge(SimulationRabbitNames.DEAD_LETTER_QUEUE);
            return null;
        });
    }
}
