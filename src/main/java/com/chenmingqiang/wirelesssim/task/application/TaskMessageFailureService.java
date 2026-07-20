package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.redis.TaskCacheInvalidationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 消息处理次数耗尽后，条件同步尚未开始执行的任务状态。 */
@Service
public class TaskMessageFailureService {

    /** 错误字段最大长度与数据库VARCHAR(1000)保持一致。 */
    private static final int MAX_ERROR_LENGTH = 1000;

    /** 执行带状态和轮次条件的任务更新。 */
    private final TaskMapper taskMapper;
    /** 重试耗尽状态提交后删除任务详情缓存。 */
    private final TaskCacheInvalidationService cacheInvalidationService;

    /** 通过构造器注入任务Mapper。 */
    public TaskMessageFailureService(
            TaskMapper taskMapper,
            TaskCacheInvalidationService cacheInvalidationService
    ) {
        this.taskMapper = taskMapper;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    /**
     * 仅把对应轮次且仍为PENDING/QUEUED的任务更新为FAILED。
     * 返回false表示任务不存在、已运行、已终态或轮次已变化，调用者不得覆盖。
     */
    @Transactional
    public boolean markDeliveryExhausted(long taskId, int attemptNo, String lastError) {
        return markWaitingTaskFailed(taskId, attemptNo, "消息处理重试耗尽：", lastError);
    }

    /** 永久不兼容任务无需重试，直接按相同状态和轮次条件关闭为FAILED。 */
    @Transactional
    public boolean markPermanentlyRejected(long taskId, int attemptNo, String reason) {
        return markWaitingTaskFailed(taskId, attemptNo, "Python Worker永久拒绝：", reason);
    }

    private boolean markWaitingTaskFailed(long taskId, int attemptNo, String prefix, String rawError) {
        if (taskId <= 0 || attemptNo <= 0) {
            return false;
        }
        String message = prefix + safe(rawError);
        if (message.length() > MAX_ERROR_LENGTH) {
            message = message.substring(0, MAX_ERROR_LENGTH);
        }
        boolean updated = taskMapper.markMessageDeliveryExhausted(taskId, attemptNo - 1, message) == 1;
        if (updated) {
            cacheInvalidationService.evictTaskAfterCommit(taskId);
        }
        return updated;
    }

    /** 空白原因使用稳定说明。 */
    private String safe(String error) {
        return error == null || error.isBlank() ? "未提供失败原因" : error;
    }
}
