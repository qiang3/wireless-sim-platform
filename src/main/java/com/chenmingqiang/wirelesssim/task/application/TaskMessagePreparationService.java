package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.redis.TaskCacheInvalidationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 在执行Worker前，把消息轮次与MySQL中的任务事实进行比对。 */
@Service
public class TaskMessagePreparationService {

    /** 读取任务并执行PENDING到QUEUED的条件更新。 */
    private final TaskMapper taskMapper;
    /** 首次消息推动任务入队后删除旧详情缓存。 */
    private final TaskCacheInvalidationService cacheInvalidationService;

    /** Spring通过构造器注入MyBatis Mapper。 */
    public TaskMessagePreparationService(
            TaskMapper taskMapper,
            TaskCacheInvalidationService cacheInvalidationService
    ) {
        this.taskMapper = taskMapper;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    /**
     * 在短事务中判断消息是否可以执行。
     * 首次提交仍为PENDING时，本方法先将其原子更新为QUEUED。
     */
    @Transactional
    public TaskMessagePreparationResult prepare(long taskId, int attemptNo) {
        if (taskId <= 0 || attemptNo <= 0) {
            throw new IllegalArgumentException("taskId和attemptNo必须为正数");
        }

        ExperimentTask task = taskMapper.findById(taskId);
        if (task == null) {
            return result(TaskMessagePreparationOutcome.TASK_NOT_FOUND, "任务不存在");
        }

        TaskMessagePreparationResult attemptResult = compareAttempt(task, attemptNo);
        if (attemptResult != null) {
            return attemptResult;
        }

        if (task.getStatus() == TaskStatus.PENDING) {
            if (taskMapper.enqueuePending(taskId) == 1) {
                cacheInvalidationService.evictAfterCommit(task.getCreatorId(), taskId);
                return result(TaskMessagePreparationOutcome.READY_TO_EXECUTE, "首次任务已从PENDING进入QUEUED");
            }
            // 状态可能刚被取消或被另一消费者推进，重新读取后再作最终判断。
            task = taskMapper.findById(taskId);
            if (task == null) {
                return result(TaskMessagePreparationOutcome.TASK_NOT_FOUND, "入队竞争后任务不存在");
            }
            attemptResult = compareAttempt(task, attemptNo);
            if (attemptResult != null) {
                return attemptResult;
            }
        }

        if (task.getStatus() == TaskStatus.QUEUED) {
            return result(TaskMessagePreparationOutcome.READY_TO_EXECUTE, "任务处于QUEUED，可尝试严格抢占");
        }
        return result(
                TaskMessagePreparationOutcome.ALREADY_HANDLED,
                "任务当前状态为" + task.getStatus() + "，不重复执行"
        );
    }

    /** 比较消息尝试号；相等时返回null，继续判断任务状态。 */
    private TaskMessagePreparationResult compareAttempt(ExperimentTask task, int attemptNo) {
        int expectedAttemptNo = task.getRetryCount() + 1;
        if (attemptNo < expectedAttemptNo) {
            return result(TaskMessagePreparationOutcome.STALE_ATTEMPT,
                    "旧轮次消息：message=" + attemptNo + ", expected=" + expectedAttemptNo);
        }
        if (attemptNo > expectedAttemptNo) {
            return result(TaskMessagePreparationOutcome.FUTURE_ATTEMPT,
                    "未来轮次消息：message=" + attemptNo + ", expected=" + expectedAttemptNo);
        }
        return null;
    }

    /** 统一创建不可变结果对象。 */
    private TaskMessagePreparationResult result(TaskMessagePreparationOutcome outcome, String detail) {
        return new TaskMessagePreparationResult(outcome, detail);
    }
}
