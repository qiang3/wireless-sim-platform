package com.chenmingqiang.wirelesssim.task.infrastructure.execution;

import com.chenmingqiang.wirelesssim.task.application.TaskExecutionClaimService;
import com.chenmingqiang.wirelesssim.task.application.TaskExecutionLifecycleService;
import com.chenmingqiang.wirelesssim.task.application.SimulationExecutionContext;
import com.chenmingqiang.wirelesssim.task.application.TaskExecutionRuntimeService;
import com.chenmingqiang.wirelesssim.task.domain.JavaMockSimulationEngine;
import com.chenmingqiang.wirelesssim.task.domain.JavaMockSimulationResult;
import com.chenmingqiang.wirelesssim.task.domain.TaskExecution;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class SimulationTaskWorker {

    private static final Logger log = LoggerFactory.getLogger(SimulationTaskWorker.class);
    private static final int TOTAL_STEPS = 10;

    private final TaskExecutionClaimService claimService;
    private final TaskExecutionRuntimeService runtimeService;
    private final TaskExecutionLifecycleService lifecycleService;
    private final JavaMockSimulationEngine simulationEngine;
    private final SimulationExecutionProperties properties;
    private final String instanceId = "local-" + UUID.randomUUID().toString().substring(0, 12);

    public SimulationTaskWorker(
            TaskExecutionClaimService claimService,
            TaskExecutionRuntimeService runtimeService,
            TaskExecutionLifecycleService lifecycleService,
            JavaMockSimulationEngine simulationEngine,
            SimulationExecutionProperties properties
    ) {
        this.claimService = claimService;
        this.runtimeService = runtimeService;
        this.lifecycleService = lifecycleService;
        this.simulationEngine = simulationEngine;
        this.properties = properties;
    }

    public boolean execute(long taskId) {
        String workerId = instanceId + ":" + Thread.currentThread().getName();
        Optional<TaskExecution> claimed = claimService.claimQueuedTask(taskId, workerId);
        if (claimed.isEmpty()) {
            log.debug("任务未抢占成功，可能已被其他Worker处理：taskId={}", taskId);
            return false;
        }

        TaskExecution execution = claimed.get();
        try {
            return executeClaimed(taskId, execution);
        } catch (RuntimeException exception) {
            String errorMessage = failureSummary(exception);
            lifecycleService.failRunningExecution(taskId, execution.getId(), errorMessage);
            log.error(
                    "Java Worker执行失败：taskId={}, executionId={}, error={}",
                    taskId,
                    execution.getId(),
                    errorMessage,
                    exception
            );
            return false;
        }
    }

    private boolean executeClaimed(long taskId, TaskExecution execution) {
        SimulationExecutionContext context = runtimeService.loadContext(taskId);

        for (int step = 1; step <= TOTAL_STEPS; step++) {
            if (stopWhenCancelled(taskId, execution.getId())) {
                return false;
            }
            delayStep();

            int progress = step * 100 / TOTAL_STEPS;
            if (!runtimeService.updateProgressAndHeartbeat(taskId, execution.getId(), progress)) {
                if (stopWhenCancelled(taskId, execution.getId())) {
                    return false;
                }
                throw new IllegalStateException("任务已不处于RUNNING状态：" + taskId);
            }
        }

        if (stopWhenCancelled(taskId, execution.getId())) {
            return false;
        }

        JavaMockSimulationResult result = simulationEngine.simulate(
                context.scenarioSnapshot(),
                context.trainingConfig()
        );
        if (!lifecycleService.completeSuccessfully(taskId, execution.getId(), result)) {
            if (stopWhenCancelled(taskId, execution.getId())) {
                return false;
            }
            throw new IllegalStateException("任务无法从RUNNING完成为SUCCEEDED：" + taskId);
        }
        log.info(
                "JAVA_MOCK执行成功并已保存结果：taskId={}, executionId={}, throughput={}, averageAoi={}, "
                        + "convergenceStep={}, scientificResult={}",
                taskId,
                execution.getId(),
                result.throughput(),
                result.averageAoi(),
                result.convergenceStep(),
                result.scientificResult()
        );
        return true;
    }

    private String failureSummary(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    private boolean stopWhenCancelled(long taskId, long executionId) {
        if (runtimeService.findTaskStatus(taskId) != TaskStatus.CANCELLED) {
            return false;
        }
        runtimeService.markExecutionCancelled(executionId);
        log.info("检测到协作取消，Java Worker停止执行：taskId={}, executionId={}", taskId, executionId);
        return true;
    }

    private void delayStep() {
        if (properties.stepDelayMs() == 0) {
            return;
        }
        try {
            Thread.sleep(properties.stepDelayMs());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Java Worker线程被中断", exception);
        }
    }
}
