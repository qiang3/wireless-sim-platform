package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationMessagingProperties;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;

/**
 * RabbitMQ业务拓扑集成测试。
 *
 * <p>测试以rabbitmq分发模式启动应用，让RabbitAdmin真实声明资源，再通过被动声明确认
 * Broker中已经存在对应交换机和队列，同时校验优先级、TTL和死信参数。</p>
 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.outbox.enabled=false"
})
class RabbitTopologyIT {

    /** 用于打开真实AMQP Channel并执行被动声明检查。 */
    @Autowired
    private RabbitTemplate rabbitTemplate;
    /** 类型化消息配置，用于核对队列参数。 */
    @Autowired
    private SimulationMessagingProperties properties;
    /** 用于确认关闭Outbox开关时后台发布调度器没有被创建。 */
    @Autowired
    private ApplicationContext applicationContext;

    /** 主交换机Bean。 */
    @Autowired
    @Qualifier("simulationTaskExchange")
    private DirectExchange taskExchange;
    /** 重试交换机Bean。 */
    @Autowired
    @Qualifier("simulationTaskRetryExchange")
    private DirectExchange retryExchange;
    /** 死信交换机Bean。 */
    @Autowired
    @Qualifier("simulationTaskDeadLetterExchange")
    private DirectExchange deadLetterExchange;

    /** 主执行队列Bean。 */
    @Autowired
    @Qualifier("simulationTaskExecuteQueue")
    private Queue executeQueue;
    /** 延迟重试队列Bean。 */
    @Autowired
    @Qualifier("simulationTaskRetryQueue")
    private Queue retryQueue;
    /** 最终死信队列Bean。 */
    @Autowired
    @Qualifier("simulationTaskDeadLetterQueue")
    private Queue deadLetterQueue;

    /** 主交换机到主队列的绑定。 */
    @Autowired
    @Qualifier("simulationTaskExecuteBinding")
    private Binding executeBinding;
    /** 重试交换机到重试队列的绑定。 */
    @Autowired
    @Qualifier("simulationTaskRetryBinding")
    private Binding retryBinding;
    /** 死信交换机到最终死信队列的绑定。 */
    @Autowired
    @Qualifier("simulationTaskDeadLetterBinding")
    private Binding deadLetterBinding;

    /** 验证Spring声明对象和RabbitMQ中的真实拓扑保持一致。 */
    @Test
    void declaresDurableTaskRetryAndDeadLetterTopology() {
        Boolean brokerResourcesExist = rabbitTemplate.execute(channel -> {
            channel.exchangeDeclarePassive(taskExchange.getName());
            channel.exchangeDeclarePassive(retryExchange.getName());
            channel.exchangeDeclarePassive(deadLetterExchange.getName());
            channel.queueDeclarePassive(executeQueue.getName());
            channel.queueDeclarePassive(retryQueue.getName());
            channel.queueDeclarePassive(deadLetterQueue.getName());
            return true;
        });

        assertThat(brokerResourcesExist).isTrue();
        assertThat(taskExchange.isDurable()).isTrue();
        assertThat(retryExchange.isDurable()).isTrue();
        assertThat(deadLetterExchange.isDurable()).isTrue();
        assertThat(executeQueue.isDurable()).isTrue();
        assertThat(retryQueue.isDurable()).isTrue();
        assertThat(deadLetterQueue.isDurable()).isTrue();

        assertThat(((Number) executeQueue.getArguments().get("x-max-priority")).intValue())
                .isEqualTo(properties.maxPriority());
        assertThat(((Number) retryQueue.getArguments().get("x-message-ttl")).longValue())
                .isEqualTo(properties.retryDelay().toMillis());
        assertThat(retryQueue.getArguments().get("x-dead-letter-exchange"))
                .isEqualTo(SimulationRabbitNames.TASK_EXCHANGE);
        assertThat(retryQueue.getArguments().get("x-dead-letter-routing-key"))
                .isEqualTo(SimulationRabbitNames.EXECUTE_ROUTING_KEY);

        assertBinding(executeBinding, SimulationRabbitNames.TASK_EXCHANGE,
                SimulationRabbitNames.EXECUTE_QUEUE, SimulationRabbitNames.EXECUTE_ROUTING_KEY);
        assertBinding(retryBinding, SimulationRabbitNames.RETRY_EXCHANGE,
                SimulationRabbitNames.RETRY_QUEUE, SimulationRabbitNames.RETRY_ROUTING_KEY);
        assertBinding(deadLetterBinding, SimulationRabbitNames.DEAD_LETTER_EXCHANGE,
                SimulationRabbitNames.DEAD_LETTER_QUEUE, SimulationRabbitNames.DEAD_LETTER_ROUTING_KEY);
        assertThat(applicationContext.containsBean("outboxPublisherScheduler")).isFalse();
    }

    /** 校验某条绑定的交换机、目标队列和路由键。 */
    private void assertBinding(Binding binding, String exchange, String queue, String routingKey) {
        assertThat(binding.getExchange()).isEqualTo(exchange);
        assertThat(binding.getDestination()).isEqualTo(queue);
        assertThat(binding.getRoutingKey()).isEqualTo(routingKey);
    }
}
