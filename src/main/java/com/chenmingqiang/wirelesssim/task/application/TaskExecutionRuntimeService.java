package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.api.ScenarioSnapshot;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskExecutionMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskExecutionRuntimeService {

    private final TaskMapper taskMapper;
    private final TaskExecutionMapper executionMapper;
    private final ObjectMapper objectMapper;

    public TaskExecutionRuntimeService(
            TaskMapper taskMapper,
            TaskExecutionMapper executionMapper,
            ObjectMapper objectMapper
    ) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public SimulationExecutionContext loadContext(long taskId) {
        ExperimentTask task = taskMapper.findById(taskId);
        if (task == null) {
            throw new IllegalStateException("执行任务不存在：" + taskId);
        }
        return new SimulationExecutionContext(
                task,
                readJson(task.getScenarioSnapshotJson(), ScenarioSnapshot.class, "任务场景快照无法解析"),
                readJson(task.getTrainingConfigJson(), TrainingConfigRequest.class, "任务训练参数无法解析")
        );
    }

    @Transactional(readOnly = true)
    public TaskStatus findTaskStatus(long taskId) {
        TaskStatus status = taskMapper.findStatusById(taskId);
        if (status == null) {
            throw new IllegalStateException("执行任务不存在：" + taskId);
        }
        return status;
    }

    @Transactional
    public boolean updateProgressAndHeartbeat(long taskId, long executionId, int progress) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("任务进度必须在0到100之间");
        }
        if (taskMapper.updateRunningProgress(taskId, progress) == 0) {
            return false;
        }
        if (executionMapper.touchHeartbeat(executionId) == 0) {
            throw new IllegalStateException("执行记录已不处于RUNNING状态：" + executionId);
        }
        return true;
    }

    @Transactional
    public void markExecutionCancelled(long executionId) {
        executionMapper.markCancelled(executionId);
    }

    private <T> T readJson(String json, Class<T> type, String message) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException(message, exception);
        }
    }
}
