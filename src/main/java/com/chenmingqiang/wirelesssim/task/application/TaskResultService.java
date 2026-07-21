package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import com.chenmingqiang.wirelesssim.task.api.TaskResultResponse;
import com.chenmingqiang.wirelesssim.task.domain.SimulationResult;
import com.chenmingqiang.wirelesssim.task.infrastructure.SimulationResultMapper;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

// Spring说明：将该类注册为业务服务Bean，其他组件可通过构造方法注入它。

@Service
/**
 * 教学注释：本文件为 application/TaskResultService.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class TaskResultService {

    /** 字段说明：`resultMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final SimulationResultMapper resultMapper;
    /** 字段说明：`objectMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final ObjectMapper objectMapper;

    /** 方法说明：`TaskResultService`封装下面这段业务或转换逻辑。 */
    public TaskResultService(SimulationResultMapper resultMapper, ObjectMapper objectMapper) {
        this.resultMapper = resultMapper;
        this.objectMapper = objectMapper;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional(readOnly = true)
    /** 方法说明：`getOwnedResult`封装下面这段业务或转换逻辑。 */
    public TaskResultResponse getOwnedResult(long creatorId, long taskId) {
        SimulationResult result = resultMapper.findOwnedByTaskId(taskId, creatorId);
        if (result == null) {
            throw new BusinessException(
                    HttpStatus.NOT_FOUND,
                    "TASK_RESULT_NOT_FOUND",
                    "任务结果不存在"
            );
        }
        Map<String, Object> metrics = readMetrics(result.getMetricsJson());
        return new TaskResultResponse(
                result.getTaskId(),
                result.getThroughput(),
                result.getAverageAoi(),
                result.getConvergenceStep(),
                longMetric(metrics, "deterministicSeed"),
                stringMetric(metrics, "simulationMode"),
                booleanMetric(metrics, "scientificResult"),
                metrics,
                result.getArtifactPath(),
                result.getCreatedAt()
        );
    }

    /**
     * 将结果元数据解析为开放结构：JAVA_MOCK与预训练GRPO拥有不同字段，不能再强制映射为同一个三字段对象。
     * 返回只读Map既保留完整模型追踪信息，也避免调用方意外修改本次响应中的元数据。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> readMetrics(String json) {
        try {
            Map<String, Object> parsed = objectMapper.readValue(json, Map.class);
            return Collections.unmodifiableMap(new LinkedHashMap<>(parsed));
        } catch (JacksonException exception) {
            throw new IllegalStateException("模拟结果指标无法解析", exception);
        }
    }

    /** 兼容旧版JAVA_MOCK顶层字段；GRPO没有该字段时明确返回null，而不是伪造0。 */
    private Long longMetric(Map<String, Object> metrics, String name) {
        Object value = metrics.get(name);
        return value instanceof Number number ? number.longValue() : null;
    }

    /** 兼容旧版JAVA_MOCK顶层字段；其他结果类型没有该字段时返回null。 */
    private String stringMetric(Map<String, Object> metrics, String name) {
        Object value = metrics.get(name);
        return value instanceof String text ? text : null;
    }

    /** 兼容旧版JAVA_MOCK顶层字段；其他结果类型没有该字段时返回null。 */
    private Boolean booleanMetric(Map<String, Object> metrics, String name) {
        Object value = metrics.get(name);
        return value instanceof Boolean flag ? flag : null;
    }
}
