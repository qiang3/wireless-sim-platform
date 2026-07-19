package com.chenmingqiang.wirelesssim.task.infrastructure.execution;

import com.chenmingqiang.wirelesssim.task.application.TaskExecutionLifecycleService;
import com.chenmingqiang.wirelesssim.task.domain.TaskExecution;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskExecutionMapper;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

// Spring说明：将该类交给Spring容器创建和管理。

@Component
// 配置说明：只有指定配置项满足条件时，Spring才创建该组件。
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "enabled",
        havingValue = "true"
)
/** 定期扫描长期未刷新心跳的 RUNNING 执行，将宕机或卡死 Worker 遗留的任务收敛为失败。 */
public class TaskTimeoutRecoveryScheduler {

    /** 字段说明：`log`保存该对象运行所需的依赖、配置或状态。 */
    private static final Logger log = LoggerFactory.getLogger(TaskTimeoutRecoveryScheduler.class);

    /** 字段说明：`executionMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskExecutionMapper executionMapper;
    /** 字段说明：`lifecycleService`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskExecutionLifecycleService lifecycleService;
    /** 字段说明：`properties`保存该对象运行所需的依赖、配置或状态。 */
    private final SimulationExecutionProperties properties;

    public TaskTimeoutRecoveryScheduler(
            TaskExecutionMapper executionMapper,
            TaskExecutionLifecycleService lifecycleService,
            SimulationExecutionProperties properties
    ) {
        this.executionMapper = executionMapper;
        this.lifecycleService = lifecycleService;
        this.properties = properties;
    }

    // 调度说明：Spring按配置的时间间隔自动调用下面的方法。

    @Scheduled(
            fixedDelayString = "${simulation.execution.recovery-scan-interval-ms:10000}",
            initialDelayString = "${simulation.execution.recovery-scan-interval-ms:10000}"
    )
    /** 批量查找超时候选并逐条恢复；单条失败不会阻断本轮其他任务。 */
    public void recoverOnce() {
        List<TaskExecution> timedOut = executionMapper.findTimedOutRunning(
                properties.heartbeatTimeoutSeconds(),
                properties.dispatchBatchSize()
        );
        for (TaskExecution execution : timedOut) {
            try {
                if (lifecycleService.recoverTimedOutExecution(
                        execution.getTaskId(),
                        execution.getId(),
                        properties.heartbeatTimeoutSeconds()
                )) {
                    log.warn(
                            "已恢复Worker心跳超时任务：taskId={}, executionId={}",
                            execution.getTaskId(),
                            execution.getId()
                    );
                }
            } catch (RuntimeException exception) {
                log.error(
                        "恢复Worker心跳超时任务失败：taskId={}, executionId={}",
                        execution.getTaskId(),
                        execution.getId(),
                        exception
                );
            }
        }
    }
}
