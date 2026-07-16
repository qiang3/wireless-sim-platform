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

@Component
@ConditionalOnProperty(
        prefix = "simulation.execution",
        name = "enabled",
        havingValue = "true"
)
public class SimulationTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(SimulationTaskDispatcher.class);

    private final TaskMapper taskMapper;
    private final TaskExecutionClaimService claimService;
    private final SimulationTaskWorker worker;
    private final ThreadPoolTaskExecutor taskExecutor;
    private final SimulationExecutionProperties properties;
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

    @Scheduled(
            fixedDelayString = "${simulation.execution.dispatch-interval-ms:1000}",
            initialDelayString = "${simulation.execution.dispatch-interval-ms:1000}"
    )
    public void dispatchOnce() {
        enqueuePendingTasks();
        submitQueuedTasks();
    }

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
