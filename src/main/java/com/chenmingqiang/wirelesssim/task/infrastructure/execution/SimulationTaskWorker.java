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

// Spring说明：将该类交给Spring容器创建和管理。

/**
 * Java Worker：抢占任务、分步汇报进度/心跳、协作取消、生成模拟结果并完成状态闭环。
 */
@Component
public class SimulationTaskWorker {

    /** 字段说明：`log`保存该对象运行所需的依赖、配置或状态。 */
    private static final Logger log = LoggerFactory.getLogger(SimulationTaskWorker.class);
    /** 把一次演示执行拆成 10 步，便于观察进度、心跳与运行中取消。 */
    private static final int TOTAL_STEPS = 10;

    /** 字段说明：`claimService`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskExecutionClaimService claimService;
    /** 字段说明：`runtimeService`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskExecutionRuntimeService runtimeService;
    /** 字段说明：`lifecycleService`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskExecutionLifecycleService lifecycleService;
    /** 字段说明：`simulationEngine`保存该对象运行所需的依赖、配置或状态。 */
    private final JavaMockSimulationEngine simulationEngine;
    /** 字段说明：`properties`保存该对象运行所需的依赖、配置或状态。 */
    private final SimulationExecutionProperties properties;
    /** 当前应用实例标识，与线程名组合后写入执行记录，便于排查由哪个 Worker 执行。 */
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

    /** 尝试抢占指定任务；未抢到不是异常，通常表示其他 Worker 已处理。 */
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

    /** 执行已抢占任务：每一步检查取消、延迟模拟工作、更新进度心跳，最后保存结果。 */
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

    /** 方法说明：`failureSummary`封装下面这段业务或转换逻辑。 */
    private String failureSummary(RuntimeException exception) {
        String message = exception.getMessage();
        return exception.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }

    /** 协作式取消：Worker 在安全检查点主动停止，而不是强制杀死线程。 */
    private boolean stopWhenCancelled(long taskId, long executionId) {
        if (runtimeService.findTaskStatus(taskId) != TaskStatus.CANCELLED) {
            return false;
        }
        runtimeService.markExecutionCancelled(executionId);
        log.info("检测到协作取消，Java Worker停止执行：taskId={}, executionId={}", taskId, executionId);
        return true;
    }

    /** 方法说明：`delayStep`封装下面这段业务或转换逻辑。 */
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
