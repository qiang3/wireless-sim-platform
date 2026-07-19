package com.chenmingqiang.wirelesssim.task.infrastructure.execution;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

// Spring说明：声明配置类，Spring启动时会读取其中的Bean定义。

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SimulationExecutionProperties.class)
@EnableScheduling
/** 异步执行基础设施配置：启用定时调度，并创建隔离的仿真任务线程池。 */
public class SimulationExecutionConfiguration {

    // Spring说明：把方法返回的对象注册为Spring Bean。

    @Bean(name = "simulationTaskExecutor")
    /**
     * 创建有界线程池。AbortPolicy 在池和队列都满时立即拒绝，Dispatcher 捕获后让任务留在数据库等待。
     */
    ThreadPoolTaskExecutor simulationTaskExecutor(SimulationExecutionProperties properties) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(properties.corePoolSize());
        executor.setMaxPoolSize(properties.maxPoolSize());
        executor.setQueueCapacity(properties.queueCapacity());
        executor.setThreadNamePrefix("simulation-worker-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(10);
        executor.initialize();
        return executor;
    }
}
