package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.TaskMessageForwardResult;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.TaskMessageForwarder;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 使用真实RabbitMQ验证重试与最终死信消息能够可靠路由并保留追踪Header。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.messaging.consumer-enabled=false",
        "simulation.outbox.enabled=false"
})
class TaskMessageForwarderIT {

    @Autowired
    private TaskMessageForwarder forwarder;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @BeforeEach
    void purgeBefore() {
        purgeQueues();
    }

    @AfterEach
    void purgeAfter() {
        purgeQueues();
    }

    @Test
    void retryMessageReachesTtlQueueWithNextAttempt() {
        TaskMessageForwardResult result = forwarder.forwardToRetry(sourceMessage(), 2, "temporary db error");

        Message retried = rabbitTemplate.receive(SimulationRabbitNames.RETRY_QUEUE, 2000);
        assertThat(result.isSuccess()).isTrue();
        assertThat(retried).isNotNull();
        assertThat(retried.getBody()).isEqualTo(sourceMessage().getBody());
        assertThat((Integer) retried.getMessageProperties().getHeader("x-delivery-attempt")).isEqualTo(2);
        assertThat((Boolean) retried.getMessageProperties().getHeader("x-final-failure")).isFalse();
    }

    @Test
    void permanentMessageReachesFinalDeadQueue() {
        TaskMessageForwardResult result = forwarder.forwardToDeadLetter(sourceMessage(), 3, "schema invalid");

        Message dead = rabbitTemplate.receive(SimulationRabbitNames.DEAD_LETTER_QUEUE, 2000);
        assertThat(result.isSuccess()).isTrue();
        assertThat(dead).isNotNull();
        assertThat((Integer) dead.getMessageProperties().getHeader("x-delivery-attempt")).isEqualTo(3);
        assertThat((String) dead.getMessageProperties().getHeader("x-last-error"))
                .isEqualTo("schema invalid");
        assertThat((Boolean) dead.getMessageProperties().getHeader("x-final-failure")).isTrue();
    }

    @Test
    void retryQueueReturnsMessageToMainQueueAfterTtl() {
        assertThat(forwarder.forwardToRetry(sourceMessage(), 2, "temporary error").isSuccess()).isTrue();

        // 当前拓扑TTL为10秒，receive最多等待12秒验证RabbitMQ死信回流。
        Message returned = rabbitTemplate.receive(SimulationRabbitNames.EXECUTE_QUEUE, 12000);

        assertThat(returned).isNotNull();
        assertThat((Integer) returned.getMessageProperties().getHeader("x-delivery-attempt")).isEqualTo(2);
        assertThat(returned.getMessageProperties().getHeaders()).containsKey("x-death");
    }

    private Message sourceMessage() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId("forward-it-event");
        properties.setCorrelationId("forward-it-event");
        properties.setType("TASK_EXECUTION_REQUESTED");
        properties.setPriority(3);
        properties.setHeader("attemptNo", 1);
        properties.setHeader("schemaVersion", 1);
        return new Message("{\"taskId\":101}".getBytes(StandardCharsets.UTF_8), properties);
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
