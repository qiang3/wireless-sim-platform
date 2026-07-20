package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.api.ScenarioSnapshot;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskExecutionMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.redis.TaskCacheInvalidationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// Spring说明：将该类注册为业务服务Bean，其他组件可通过构造方法注入它。

/**
 * Worker 运行期服务：加载不可变输入快照、读取状态，并原子更新进度与心跳。
 */
@Service
public class TaskExecutionRuntimeService {

    /** 字段说明：`taskMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskMapper taskMapper;
    /** 字段说明：`executionMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskExecutionMapper executionMapper;
    /** 字段说明：`objectMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final ObjectMapper objectMapper;
    /** 进度与心跳提交后删除旧详情缓存。 */
    private final TaskCacheInvalidationService cacheInvalidationService;

    public TaskExecutionRuntimeService(
            TaskMapper taskMapper,
            TaskExecutionMapper executionMapper,
            ObjectMapper objectMapper,
            TaskCacheInvalidationService cacheInvalidationService
    ) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.objectMapper = objectMapper;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional(readOnly = true)
    /** 从任务记录还原场景快照和训练配置，组成模拟引擎需要的完整上下文。 */
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

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional(readOnly = true)
    /** 方法说明：`findTaskStatus`封装下面这段业务或转换逻辑。 */
    public TaskStatus findTaskStatus(long taskId) {
        TaskStatus status = taskMapper.findStatusById(taskId);
        if (status == null) {
            throw new IllegalStateException("执行任务不存在：" + taskId);
        }
        return status;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /**
     * 在一个事务中更新任务进度和执行记录心跳：两项都成功才提交；
     * 心跳更新失败会抛异常，使之前的进度更新一并回滚，避免部分成功状态。
     */
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
        cacheInvalidationService.evictTaskAfterCommit(taskId);
        return true;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** Worker 观察到任务已取消后，把本次执行记录也标记为 CANCELLED。 */
    public void markExecutionCancelled(long executionId) {
        executionMapper.markCancelled(executionId);
    }

    /** 方法说明：`readJson`封装下面这段业务或转换逻辑。 */
    private <T> T readJson(String json, Class<T> type, String message) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException(message, exception);
        }
    }
}
