package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;
import com.chenmingqiang.wirelesssim.task.domain.ExecutionStatus;
import com.chenmingqiang.wirelesssim.task.domain.TaskExecution;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskExecutionMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskExecutionClaimService {

    private static final int MAX_WORKER_ID_LENGTH = 100;

    private final TaskMapper taskMapper;
    private final TaskExecutionMapper executionMapper;

    public TaskExecutionClaimService(TaskMapper taskMapper, TaskExecutionMapper executionMapper) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
    }

    @Transactional
    public boolean enqueuePendingTask(long taskId) {
        return taskMapper.enqueuePending(taskId) == 1;
    }

    @Transactional
    public Optional<TaskExecution> claimQueuedTask(long taskId, String workerId) {
        validateWorkerId(workerId);

        if (taskMapper.claimQueuedForExecution(taskId) == 0) {
            return Optional.empty();
        }

        ExperimentTask task = taskMapper.findById(taskId);
        if (task == null) {
            throw new IllegalStateException("抢占成功后未找到任务：" + taskId);
        }

        int attemptNo = task.getRetryCount() + 1;
        TaskExecution execution = new TaskExecution();
        execution.setTaskId(taskId);
        execution.setAttemptNo(attemptNo);
        execution.setWorkerId(workerId);
        execution.setStatus(ExecutionStatus.RUNNING);
        executionMapper.insertRunning(execution);

        TaskExecution saved = executionMapper.findByTaskIdAndAttemptNo(taskId, attemptNo);
        if (saved == null) {
            throw new IllegalStateException("执行记录保存后无法读取：taskId=" + taskId);
        }
        return Optional.of(saved);
    }

    private void validateWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId不能为空");
        }
        if (workerId.length() > MAX_WORKER_ID_LENGTH) {
            throw new IllegalArgumentException("workerId长度不能超过100个字符");
        }
    }
}
