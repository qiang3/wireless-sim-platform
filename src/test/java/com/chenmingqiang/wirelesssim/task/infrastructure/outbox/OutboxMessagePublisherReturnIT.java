package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 使用真实RabbitMQ验证mandatory消息不可路由时返回RETURNED。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.outbox.enabled=false"
})
class OutboxMessagePublisherReturnIT {

    /** 被测试的单消息发布器；后台调度器因Outbox开关关闭而不会启动。 */
    @Autowired
    private OutboxMessagePublisher publisher;

    /** 向真实主交换机发送不存在的路由键，验证ACK不能掩盖Return。 */
    @Test
    void returnsReturnedForRealUnroutableMandatoryMessage() {
        OutboxPublishResult result = publisher.publishTo(
                newEvent(),
                SimulationRabbitNames.TASK_EXCHANGE,
                "simulation.task.missing.route"
        );

        assertThat(result.outcome()).isEqualTo(OutboxPublishOutcome.RETURNED);
        assertThat(result.isSuccess()).isFalse();
        assertThat(result.detail()).contains("NO_ROUTE", "simulation.task.missing.route");
    }

    /** 创建真实Return测试使用的完整内存事件。 */
    private OutboxEvent newEvent() {
        String eventId = UUID.randomUUID().toString();
        OutboxEvent event = new OutboxEvent();
        event.setId(801L);
        event.setEventId(eventId);
        event.setAggregateType("EXPERIMENT_TASK");
        event.setAggregateId(3001L);
        event.setEventType("TASK_EXECUTION_REQUESTED");
        event.setAttemptNo(1);
        event.setSchemaVersion(1);
        event.setPayloadJson("{\"eventId\":\"" + eventId + "\",\"taskId\":3001}");
        event.setPriority(3);
        event.setOccurredAt(LocalDateTime.now());
        return event;
    }
}
