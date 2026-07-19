package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 仿真任务RabbitMQ拓扑配置。
 *
 * <p>Spring Boot自动创建的RabbitAdmin会发现这些Declarable Bean，并在RabbitMQ中声明对应资源。
 * 配置只在分发模式为rabbitmq时生效；当前生产默认使用RabbitMQ，mysql模式保留为本地回退方案。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulationMessagingProperties.class)
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "dispatch-mode",
        havingValue = "rabbitmq"
)
public class SimulationRabbitTopologyConfiguration {

    /** 声明持久化主交换机，正常任务执行事件从这里进入主队列。 */
    @Bean
    public DirectExchange simulationTaskExchange() {
        return ExchangeBuilder.directExchange(SimulationRabbitNames.TASK_EXCHANGE)
                .durable(true)
                .build();
    }

    /** 声明持久化重试交换机，临时失败消息先发送到这里。 */
    @Bean
    public DirectExchange simulationTaskRetryExchange() {
        return ExchangeBuilder.directExchange(SimulationRabbitNames.RETRY_EXCHANGE)
                .durable(true)
                .build();
    }

    /** 声明持久化死信交换机，保存不应继续自动处理的消息。 */
    @Bean
    public DirectExchange simulationTaskDeadLetterExchange() {
        return ExchangeBuilder.directExchange(SimulationRabbitNames.DEAD_LETTER_EXCHANGE)
                .durable(true)
                .build();
    }

    /** 声明带优先级能力的持久化主执行队列。 */
    @Bean
    public Queue simulationTaskExecuteQueue(SimulationMessagingProperties properties) {
        return QueueBuilder.durable(SimulationRabbitNames.EXECUTE_QUEUE)
                .maxPriority(properties.maxPriority())
                .build();
    }

    /**
     * 声明延迟重试队列。
     *
     * <p>该队列没有消费者。消息等待TTL到期后，由RabbitMQ通过死信机制重新投递到主交换机。</p>
     */
    @Bean
    public Queue simulationTaskRetryQueue(SimulationMessagingProperties properties) {
        return QueueBuilder.durable(SimulationRabbitNames.RETRY_QUEUE)
                .ttl(properties.retryDelayMillis())
                .deadLetterExchange(SimulationRabbitNames.TASK_EXCHANGE)
                .deadLetterRoutingKey(SimulationRabbitNames.EXECUTE_ROUTING_KEY)
                .build();
    }

    /** 声明持久化最终死信队列；它不自动返回主队列，避免形成无限循环。 */
    @Bean
    public Queue simulationTaskDeadLetterQueue() {
        return QueueBuilder.durable(SimulationRabbitNames.DEAD_LETTER_QUEUE).build();
    }

    /** 使用执行路由键把主交换机与主队列绑定。 */
    @Bean
    public Binding simulationTaskExecuteBinding(
            @Qualifier("simulationTaskExecuteQueue") Queue queue,
            @Qualifier("simulationTaskExchange") DirectExchange exchange
    ) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(SimulationRabbitNames.EXECUTE_ROUTING_KEY);
    }

    /** 使用重试路由键把重试交换机与重试队列绑定。 */
    @Bean
    public Binding simulationTaskRetryBinding(
            @Qualifier("simulationTaskRetryQueue") Queue queue,
            @Qualifier("simulationTaskRetryExchange") DirectExchange exchange
    ) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(SimulationRabbitNames.RETRY_ROUTING_KEY);
    }

    /** 使用死信路由键把死信交换机与最终死信队列绑定。 */
    @Bean
    public Binding simulationTaskDeadLetterBinding(
            @Qualifier("simulationTaskDeadLetterQueue") Queue queue,
            @Qualifier("simulationTaskDeadLetterExchange") DirectExchange exchange
    ) {
        return BindingBuilder.bind(queue)
                .to(exchange)
                .with(SimulationRabbitNames.DEAD_LETTER_ROUTING_KEY);
    }
}
