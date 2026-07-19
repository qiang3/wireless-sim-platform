package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import com.chenmingqiang.wirelesssim.task.api.TaskResultResponse;
import com.chenmingqiang.wirelesssim.task.domain.SimulationMetrics;
import com.chenmingqiang.wirelesssim.task.domain.SimulationResult;
import com.chenmingqiang.wirelesssim.task.infrastructure.SimulationResultMapper;
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
        SimulationMetrics metrics = readMetrics(result.getMetricsJson());
        return new TaskResultResponse(
                result.getTaskId(),
                result.getThroughput(),
                result.getAverageAoi(),
                result.getConvergenceStep(),
                metrics.deterministicSeed(),
                metrics.simulationMode(),
                metrics.scientificResult(),
                result.getArtifactPath(),
                result.getCreatedAt()
        );
    }

    /** 方法说明：`readMetrics`封装下面这段业务或转换逻辑。 */
    private SimulationMetrics readMetrics(String json) {
        try {
            return objectMapper.readValue(json, SimulationMetrics.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("模拟结果指标无法解析", exception);
        }
    }
}
