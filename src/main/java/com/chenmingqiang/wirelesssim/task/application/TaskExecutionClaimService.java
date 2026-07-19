package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;
import com.chenmingqiang.wirelesssim.task.domain.ExecutionStatus;
import com.chenmingqiang.wirelesssim.task.domain.TaskExecution;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskExecutionMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Spring说明：将该类注册为业务服务Bean，其他组件可通过构造方法注入它。

/**
 * 执行抢占服务：通过带状态条件的 SQL，保证一个 QUEUED 任务只被一个 Worker 抢到。
 */
@Service
public class TaskExecutionClaimService {

    /** 字段说明：`MAX_WORKER_ID_LENGTH`保存该对象运行所需的依赖、配置或状态。 */
    private static final int MAX_WORKER_ID_LENGTH = 100;

    /** 字段说明：`taskMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskMapper taskMapper;
    /** 字段说明：`executionMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskExecutionMapper executionMapper;

    /** 方法说明：`TaskExecutionClaimService`封装下面这段业务或转换逻辑。 */
    public TaskExecutionClaimService(TaskMapper taskMapper, TaskExecutionMapper executionMapper) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** 原子地把 PENDING 任务变为 QUEUED；已被其他调度器处理时返回 false。 */
    public boolean enqueuePendingTask(long taskId) {
        return taskMapper.enqueuePending(taskId) == 1;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /**
     * 原子抢占 QUEUED 任务并创建本次 RUNNING 执行记录。
     * 两项写入在同一事务中，任何一步失败都会整体回滚。
     */
    public Optional<TaskExecution> claimQueuedTask(long taskId, String workerId) {
        validateWorkerId(workerId);

        if (taskMapper.claimQueuedForExecution(taskId) == 0) {
            // UPDATE ... WHERE status='QUEUED' 影响 0 行，说明其他 Worker 已先抢占或状态已变化。
            return Optional.empty();
        }

        ExperimentTask task = taskMapper.findById(taskId);
        if (task == null) {
            throw new IllegalStateException("抢占成功后未找到任务：" + taskId);
        }

        int attemptNo = task.getRetryCount() + 1; // 首次执行为 1，每次重试形成新的执行记录。
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

    /**
     * 按RabbitMQ消息携带的准确轮次抢占任务。
     * SQL同时校验QUEUED和retry_count，防止旧消息执行后续人工重试。
     */
    @Transactional
    public Optional<TaskExecution> claimQueuedTask(long taskId, int attemptNo, String workerId) {
        validateWorkerId(workerId);
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo必须为正数");
        }

        int expectedRetryCount = attemptNo - 1;
        if (taskMapper.claimQueuedForExecutionAttempt(taskId, expectedRetryCount) == 0) {
            return Optional.empty();
        }

        TaskExecution execution = new TaskExecution();
        execution.setTaskId(taskId);
        execution.setAttemptNo(attemptNo);
        execution.setWorkerId(workerId);
        execution.setStatus(ExecutionStatus.RUNNING);
        executionMapper.insertRunning(execution);

        TaskExecution saved = executionMapper.findByTaskIdAndAttemptNo(taskId, attemptNo);
        if (saved == null) {
            throw new IllegalStateException(
                    "严格抢占后无法读取执行记录：taskId=" + taskId + ", attemptNo=" + attemptNo
            );
        }
        return Optional.of(saved);
    }

    /** 方法说明：`validateWorkerId`封装下面这段业务或转换逻辑。 */
    private void validateWorkerId(String workerId) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException("workerId不能为空");
        }
        if (workerId.length() > MAX_WORKER_ID_LENGTH) {
            throw new IllegalArgumentException("workerId长度不能超过100个字符");
        }
    }
}
