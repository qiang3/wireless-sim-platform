package com.chenmingqiang.wirelesssim.task.infrastructure.execution;

import com.chenmingqiang.wirelesssim.task.application.TaskExecutionClaimService;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

// Spring说明：将该类交给Spring容器创建和管理。

@Component
// 配置说明：只有指定配置项满足条件时，Spring才创建该组件。
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "enabled",
        havingValue = "true"
)
/** 定时调度器：扫描数据库中的候选任务，并把 QUEUED 任务提交到受控线程池。 */
public class SimulationTaskDispatcher {

    /** 字段说明：`log`保存该对象运行所需的依赖、配置或状态。 */
    private static final Logger log = LoggerFactory.getLogger(SimulationTaskDispatcher.class);

    /** 字段说明：`taskMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskMapper taskMapper;
    /** 字段说明：`claimService`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskExecutionClaimService claimService;
    /** 字段说明：`worker`保存该对象运行所需的依赖、配置或状态。 */
    private final SimulationTaskWorker worker;
    /** 字段说明：`taskExecutor`保存该对象运行所需的依赖、配置或状态。 */
    private final ThreadPoolTaskExecutor taskExecutor;
    /** 字段说明：`properties`保存该对象运行所需的依赖、配置或状态。 */
    private final SimulationExecutionProperties properties;
    /** 当前 JVM 已提交线程池但尚未结束的任务 ID；仅做本机去重，跨实例抢占仍由数据库保证。 */
    private final Set<Long> inFlightTaskIds = ConcurrentHashMap.newKeySet();

    public SimulationTaskDispatcher(
            TaskMapper taskMapper,
            TaskExecutionClaimService claimService,
            SimulationTaskWorker worker,
            @Qualifier("simulationTaskExecutor") ThreadPoolTaskExecutor taskExecutor,
            SimulationExecutionProperties properties
    ) {
        this.taskMapper = taskMapper;
        this.claimService = claimService;
        this.worker = worker;
        this.taskExecutor = taskExecutor;
        this.properties = properties;
    }

    // 调度说明：Spring按配置的时间间隔自动调用下面的方法。

    @Scheduled(
            fixedDelayString = "${simulation.execution.dispatch-interval-ms:10000}",
            initialDelayString = "${simulation.execution.dispatch-interval-ms:10000}"
    )
    /** 每轮先把 PENDING 入队，再把 QUEUED 提交 Worker。fixedDelay 从上轮结束后开始计时。 */
    public void dispatchOnce() {
        enqueuePendingTasks();
        submitQueuedTasks();
    }

    /** 方法说明：`enqueuePendingTasks`封装下面这段业务或转换逻辑。 */
    private void enqueuePendingTasks() {
        List<Long> candidateIds = taskMapper.findPendingCandidateIds(properties.dispatchBatchSize());
        for (Long taskId : candidateIds) {
            try {
                claimService.enqueuePendingTask(taskId);
            } catch (RuntimeException exception) {
                log.error("任务入队失败，将等待下次扫描：taskId={}", taskId, exception);
            }
        }
    }

    /** 将候选任务提交线程池；队列满时不丢任务，保留 QUEUED 等待下一轮。 */
    private void submitQueuedTasks() {
        List<Long> candidateIds = taskMapper.findQueuedCandidateIds(properties.dispatchBatchSize());
        for (Long taskId : candidateIds) {
            if (!inFlightTaskIds.add(taskId)) {
                continue;
            }
            try {
                taskExecutor.execute(() -> executeAndRelease(taskId));
            } catch (TaskRejectedException exception) {
                inFlightTaskIds.remove(taskId);
                log.warn("仿真线程池已满，任务保留为QUEUED等待下次扫描：taskId={}", taskId);
            }
        }
    }

    /** 方法说明：`executeAndRelease`封装下面这段业务或转换逻辑。 */
    private void executeAndRelease(long taskId) {
        try {
            worker.execute(taskId);
        } catch (RuntimeException exception) {
            log.error("Java Worker骨架执行异常：taskId={}", taskId, exception);
        } finally {
            inFlightTaskIds.remove(taskId);
        }
    }
}
