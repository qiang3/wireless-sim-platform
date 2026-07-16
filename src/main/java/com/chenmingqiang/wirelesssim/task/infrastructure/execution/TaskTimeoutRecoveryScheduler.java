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

@Component
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "enabled",
        havingValue = "true"
)
public class TaskTimeoutRecoveryScheduler {

    private static final Logger log = LoggerFactory.getLogger(TaskTimeoutRecoveryScheduler.class);

    private final TaskExecutionMapper executionMapper;
    private final TaskExecutionLifecycleService lifecycleService;
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

    @Scheduled(
            fixedDelayString = "${simulation.execution.recovery-scan-interval-ms:10000}",
            initialDelayString = "${simulation.execution.recovery-scan-interval-ms:10000}"
    )
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
