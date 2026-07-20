package com.chenmingqiang.wirelesssim.task.infrastructure.redis;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 注册阶段8.7所需的Redis类型安全配置。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulationRedisProperties.class)
public class SimulationRedisConfiguration {
}
