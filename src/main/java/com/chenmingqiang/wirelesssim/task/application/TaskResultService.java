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

@Service
public class TaskResultService {

    private final SimulationResultMapper resultMapper;
    private final ObjectMapper objectMapper;

    public TaskResultService(SimulationResultMapper resultMapper, ObjectMapper objectMapper) {
        this.resultMapper = resultMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
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

    private SimulationMetrics readMetrics(String json) {
        try {
            return objectMapper.readValue(json, SimulationMetrics.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("模拟结果指标无法解析", exception);
        }
    }
}
