package com.chenmingqiang.wirelesssim.task.infrastructure.worker;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 启用Worker API的类型安全配置绑定。 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(WorkerApiProperties.class)
public class WorkerApiConfiguration {
}
