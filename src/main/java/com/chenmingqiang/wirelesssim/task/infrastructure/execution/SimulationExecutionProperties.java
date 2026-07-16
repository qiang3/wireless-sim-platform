package com.chenmingqiang.wirelesssim.task.infrastructure.execution;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties("simulation.execution")
public record SimulationExecutionProperties(
        boolean enabled,

        @Min(value = 1, message = "仿真核心线程数不能小于1")
        @Max(value = 64, message = "仿真核心线程数不能大于64")
        int corePoolSize,

        @Min(value = 1, message = "仿真最大线程数不能小于1")
        @Max(value = 64, message = "仿真最大线程数不能大于64")
        int maxPoolSize,

        @Min(value = 0, message = "仿真线程池队列容量不能小于0")
        @Max(value = 10000, message = "仿真线程池队列容量不能大于10000")
        int queueCapacity,

        @Min(value = 100, message = "任务扫描间隔不能小于100毫秒")
        long dispatchIntervalMs,

        @Min(value = 1, message = "单次调度任务数不能小于1")
        @Max(value = 1000, message = "单次调度任务数不能大于1000")
        int dispatchBatchSize,

        @Min(value = 0, message = "模拟步骤延迟不能小于0")
        long stepDelayMs,

        @Min(value = 1000, message = "超时恢复扫描间隔不能小于1000毫秒")
        long recoveryScanIntervalMs,

        @Min(value = 5, message = "Worker心跳超时不能小于5秒")
        long heartbeatTimeoutSeconds
) {
    public SimulationExecutionProperties {
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("仿真最大线程数不能小于核心线程数");
        }
    }
}
