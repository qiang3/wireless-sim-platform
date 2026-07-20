package com.chenmingqiang.wirelesssim.task.infrastructure.redis;

import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 协调MySQL事务与Redis缓存删除的时序。
 *
 * <p>Redis不参与MySQL事务，因此数据库写方法只登记提交后回调；
 * 事务真正提交成功才删除缓存，回滚时不执行删除。</p>
 */
@Service
public class TaskCacheInvalidationService {

    /** 具体执行Redis删除的缓存适配器。 */
    private final TaskDetailCache taskDetailCache;
    /** 在只有taskId的Worker链路中查询任务所有者。 */
    private final TaskMapper taskMapper;

    /** 注入任务详情缓存。 */
    public TaskCacheInvalidationService(TaskDetailCache taskDetailCache, TaskMapper taskMapper) {
        this.taskDetailCache = taskDetailCache;
        this.taskMapper = taskMapper;
    }

    /** 当前存在活跃事务时提交后删除，否则立即删除。 */
    public void evictAfterCommit(long userId, long taskId) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    taskDetailCache.evict(userId, taskId);
                }
            });
            return;
        }
        taskDetailCache.evict(userId, taskId);
    }

    /** 已经离开数据库事务的Worker可直接调用。 */
    public void evictNow(long userId, long taskId) {
        taskDetailCache.evict(userId, taskId);
    }

    /** Worker只持有taskId时查询所有者，并在当前事务成功提交后删除缓存。 */
    public void evictTaskAfterCommit(long taskId) {
        Long userId = taskMapper.findCreatorIdById(taskId);
        if (userId != null) {
            evictAfterCommit(userId, taskId);
        }
    }

    /** 已离开事务的执行链路按taskId查询所有者并立即删除缓存。 */
    public void evictTaskNow(long taskId) {
        Long userId = taskMapper.findCreatorIdById(taskId);
        if (userId != null) {
            evictNow(userId, taskId);
        }
    }
}
