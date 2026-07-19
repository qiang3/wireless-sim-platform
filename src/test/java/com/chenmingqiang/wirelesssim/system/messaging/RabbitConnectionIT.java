package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * RabbitMQ真实连接集成测试。
 *
 * <p>该测试不创建业务交换机或队列，只验证Spring Boot能够使用application.yml中的
 * 地址、账号和虚拟主机建立AMQP连接，并成功打开Channel。</p>
 */
@SpringBootTest(properties = "simulation.execution.enabled=false")
class RabbitConnectionIT {

    /** Spring Boot根据spring.rabbitmq.*配置自动创建的消息操作模板。 */
    @Autowired
    private RabbitTemplate rabbitTemplate;

    /** 验证连接与Channel都已成功打开，证明本地RabbitMQ基础环境可以被Java应用访问。 */
    @Test
    void connectsToConfiguredRabbitMqVirtualHost() {
        Boolean connectionAndChannelOpen = rabbitTemplate.execute(channel ->
                channel.isOpen() && channel.getConnection().isOpen()
        );

        assertThat(connectionAndChannelOpen).isTrue();
    }
}
