package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.domain.JavaMockSimulationResult;
import com.chenmingqiang.wirelesssim.task.domain.GrpoInferenceResult;
import com.chenmingqiang.wirelesssim.task.domain.SimulationMetrics;
import com.chenmingqiang.wirelesssim.task.domain.SimulationResult;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.SimulationResultMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskExecutionMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.redis.TaskCacheInvalidationService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// Spring说明：将该类注册为业务服务Bean，其他组件可通过构造方法注入它。

/**
 * 执行生命周期服务：以事务维护任务、执行记录和模拟结果之间的一致性。
 */
@Service
public class TaskExecutionLifecycleService {

    public static final String HEARTBEAT_TIMEOUT_ERROR = "Worker心跳超时";
    /** 字段说明：`MAX_ERROR_LENGTH`保存该对象运行所需的依赖、配置或状态。 */
    private static final int MAX_ERROR_LENGTH = 1000;

    /** 字段说明：`taskMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskMapper taskMapper;
    /** 字段说明：`executionMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskExecutionMapper executionMapper;
    /** 字段说明：`resultMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final SimulationResultMapper resultMapper;
    /** 字段说明：`objectMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final ObjectMapper objectMapper;
    /** 终态事务提交后删除旧详情缓存。 */
    private final TaskCacheInvalidationService cacheInvalidationService;

    public TaskExecutionLifecycleService(
            TaskMapper taskMapper,
            TaskExecutionMapper executionMapper,
            SimulationResultMapper resultMapper,
            ObjectMapper objectMapper,
            TaskCacheInvalidationService cacheInvalidationService
    ) {
        this.taskMapper = taskMapper;
        this.executionMapper = executionMapper;
        this.resultMapper = resultMapper;
        this.objectMapper = objectMapper;
        this.cacheInvalidationService = cacheInvalidationService;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** 成功闭环：任务→SUCCEEDED、执行记录→SUCCEEDED、结果入库，三步全部成功才提交。 */
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
        cacheInvalidationService.evictTaskAfterCommit(taskId);
        return true;
    }

    /**
     * 保存Python预训练GRPO的真实评估结果。任务、执行记录和结果仍在同一MySQL事务内完成，
     * 因此Python进程无需也不允许直接连接业务数据库。
     */
    @Transactional
    public boolean completeGrpoInference(long taskId, long executionId, GrpoInferenceResult inferenceResult) {
        if (taskMapper.markSucceeded(taskId) == 0) {
            return false;
        }
        if (executionMapper.markSucceeded(executionId) == 0) {
            throw new IllegalStateException("GRPO执行记录无法完成：" + executionId);
        }

        SimulationResult result = new SimulationResult();
        result.setTaskId(taskId);
        result.setThroughput(inferenceResult.throughputMean());
        result.setAverageAoi(null);
        result.setConvergenceStep(null);
        result.setMetricsJson(writeJson(new GrpoMetrics(
                "PRETRAINED_MODEL",
                false,
                inferenceResult.modelId(),
                inferenceResult.checkpointSha256(),
                inferenceResult.baseSeed(),
                "Mbit/episode",
                inferenceResult.throughputStd(),
                inferenceResult.throughputMin(),
                inferenceResult.throughputMax(),
                inferenceResult.totalTimesteps(),
                inferenceResult.totalEvaluationTimeSeconds(),
                null
        )));
        result.setArtifactPath(inferenceResult.artifactPath());
        resultMapper.insert(result);
        cacheInvalidationService.evictTaskAfterCommit(taskId);
        return true;
    }

    /** metrics_json中的结构化模型元数据；averageAoi保持null表示本模型未计算该指标。 */
    private record GrpoMetrics(
            String evaluationMode,
            boolean trainingPerformed,
            String modelId,
            String checkpointSha256,
            long baseSeed,
            String throughputUnit,
            java.math.BigDecimal throughputStd,
            java.math.BigDecimal throughputMin,
            java.math.BigDecimal throughputMax,
            int totalTimesteps,
            java.math.BigDecimal totalEvaluationTimeSeconds,
            java.math.BigDecimal averageAoi
    ) {
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** 失败闭环；若用户已先取消任务，则执行记录跟随取消而不覆盖为 FAILED。 */
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
        cacheInvalidationService.evictTaskAfterCommit(taskId);
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /**
     * 超时恢复采用“再次带超时条件更新”：扫描后若 Worker 已刷新心跳，UPDATE 会影响 0 行，
     * 从而避免把已经恢复工作的执行误判为失败。
     */
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
        cacheInvalidationService.evictTaskAfterCommit(taskId);
        return true;
    }

    /** 方法说明：`normalizeError`封装下面这段业务或转换逻辑。 */
    private String normalizeError(String errorMessage) {
        String normalized = errorMessage == null || errorMessage.isBlank()
                ? "Worker执行失败"
                : errorMessage.trim();
        return normalized.length() <= MAX_ERROR_LENGTH
                ? normalized
                : normalized.substring(0, MAX_ERROR_LENGTH);
    }

    /** 方法说明：`writeJson`封装下面这段业务或转换逻辑。 */
    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException("模拟指标序列化失败", exception);
        }
    }
}
