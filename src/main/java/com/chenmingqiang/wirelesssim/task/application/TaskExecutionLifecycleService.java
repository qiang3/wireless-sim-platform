package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.domain.JavaMockSimulationResult;
import com.chenmingqiang.wirelesssim.task.domain.SimulationMetrics;
import com.chenmingqiang.wirelesssim.task.domain.SimulationResult;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.SimulationResultMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskExecutionMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskExecutionLifecycleService {

    public static final String HEARTBEAT_TIMEOUT_ERROR = "Worker心跳超时";
    private static final int MAX_ERROR_LENGTH = 1000;

    private final TaskMapper taskMapper;
    private final TaskExecutionMapper executionMapper;
    private final SimulationResultMapper resultMapper;
    private final ObjectMapper objectMapper;

    public TaskExecutionLifecycleService(
            TaskMapper taskMapper,
            TaskExecutionMapper executionMapper,
            SimulationResultMapper resultMapper,
            ObjectMapper objectMapper
    ) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.resultMapper = resultMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public boolean completeSuccessfully(
            long taskId,
            long executionId,
            JavaMockSimulationResult mockResult
    ) {
        if (taskMapper.markSucceeded(taskId) == 0) {
            return false;
        }
        if (executionMapper.markSucceeded(executionId) == 0) {
            throw new IllegalStateException("执行记录无法完成：" + executionId);
        }

        SimulationMetrics metrics = new SimulationMetrics(
                mockResult.deterministicSeed(),
                mockResult.simulationMode(),
                mockResult.scientificResult()
        );
        SimulationResult result = new SimulationResult();
        result.setTaskId(taskId);
        result.setThroughput(mockResult.throughput());
        result.setAverageAoi(mockResult.averageAoi());
        result.setConvergenceStep(mockResult.convergenceStep());
        result.setMetricsJson(writeJson(metrics));
        result.setArtifactPath(null);
        resultMapper.insert(result);
        return true;
    }

    @Transactional
    public void failRunningExecution(long taskId, long executionId, String rawErrorMessage) {
        TaskStatus status = taskMapper.findStatusById(taskId);
        if (status == TaskStatus.CANCELLED) {
            executionMapper.markCancelled(executionId);
            return;
        }
        if (status != TaskStatus.RUNNING) {
            return;
        }

        String errorMessage = normalizeError(rawErrorMessage);
        if (taskMapper.markFailed(taskId, errorMessage) == 0) {
            return;
        }
        if (executionMapper.markFailed(executionId, errorMessage) == 0) {
            throw new IllegalStateException("执行记录无法标记失败：" + executionId);
        }
    }

    @Transactional
    public boolean recoverTimedOutExecution(
            long taskId,
            long executionId,
            long timeoutSeconds
    ) {
        if (executionMapper.markFailedIfTimedOut(
                executionId,
                timeoutSeconds,
                HEARTBEAT_TIMEOUT_ERROR
        ) == 0) {
            return false;
        }
        if (taskMapper.markFailed(taskId, HEARTBEAT_TIMEOUT_ERROR) == 0) {
            throw new IllegalStateException("超时执行对应任务已不处于RUNNING状态：" + taskId);
        }
        return true;
    }

    private String normalizeError(String errorMessage) {
        String normalized = errorMessage == null || errorMessage.isBlank()
                ? "Worker执行失败"
                : errorMessage.trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("模拟指标序列化失败", exception);
        }
    }
}
