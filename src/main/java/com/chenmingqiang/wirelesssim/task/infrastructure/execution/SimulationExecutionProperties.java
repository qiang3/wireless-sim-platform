package com.chenmingqiang.wirelesssim.task.infrastructure.execution;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// 校验说明：启用该类型上的Bean Validation参数约束。

@Validated
// 配置说明：把application.yml中对应前缀的配置绑定到该类型。
@ConfigurationProperties("simulation.execution")
/**
 * 教学注释：本文件为 infrastructure/execution/SimulationExecutionProperties.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record SimulationExecutionProperties(
        boolean enabled,

        // 校验说明：限制数值的最小值。

        @Min(value = 1, message = "仿真核心线程数不能小于1")
        // 校验说明：限制数值的最大值。
        @Max(value = 64, message = "仿真核心线程数不能大于64")
        int corePoolSize,

        // 校验说明：限制数值的最小值。

        @Min(value = 1, message = "仿真最大线程数不能小于1")
        // 校验说明：限制数值的最大值。
        @Max(value = 64, message = "仿真最大线程数不能大于64")
        int maxPoolSize,

        // 校验说明：限制数值的最小值。

        @Min(value = 0, message = "仿真线程池队列容量不能小于0")
        // 校验说明：限制数值的最大值。
        @Max(value = 10000, message = "仿真线程池队列容量不能大于10000")
        int queueCapacity,

        // 校验说明：限制数值的最小值。

        @Min(value = 100, message = "任务扫描间隔不能小于100毫秒")
        long dispatchIntervalMs,

        // 校验说明：限制数值的最小值。

        @Min(value = 1, message = "单次调度任务数不能小于1")
        // 校验说明：限制数值的最大值。
        @Max(value = 1000, message = "单次调度任务数不能大于1000")
        int dispatchBatchSize,

        // 校验说明：限制数值的最小值。

        @Min(value = 0, message = "模拟步骤延迟不能小于0")
        long stepDelayMs,

        // 校验说明：限制数值的最小值。

        @Min(value = 1000, message = "超时恢复扫描间隔不能小于1000毫秒")
        long recoveryScanIntervalMs,

        // 校验说明：限制数值的最小值。

        @Min(value = 5, message = "Worker心跳超时不能小于5秒")
        long heartbeatTimeoutSeconds
) {
    public SimulationExecutionProperties {
        if (maxPoolSize < corePoolSize) {
            throw new IllegalArgumentException("仿真最大线程数不能小于核心线程数");
        }
    }
}
