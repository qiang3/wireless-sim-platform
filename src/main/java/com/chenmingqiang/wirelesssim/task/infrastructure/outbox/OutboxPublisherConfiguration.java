package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 注册Outbox发布器配置对象。
 *
 * <p>本类目前只负责配置绑定；真正的定时发布器会在8.4后续步骤中增加，
 * 并限制为RabbitMQ调度模式才启动。</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxPublisherProperties.class)
public class OutboxPublisherConfiguration {
}
