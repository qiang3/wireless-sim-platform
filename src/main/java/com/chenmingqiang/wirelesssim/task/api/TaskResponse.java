package com.chenmingqiang.wirelesssim.task.api;

import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import java.time.LocalDateTime;

public record TaskResponse(
        Long id,
        String taskNo,
        Long scenarioId,
        ScenarioSnapshot scenarioSnapshot,
        TaskAlgorithm algorithm,
        TrainingConfigRequest trainingConfig,
        Integer priority,
        TaskStatus status,
        Integer progress,
        Integer retryCount,
        Integer maxRetryCount,
        String errorMessage,
        Integer version,
        LocalDateTime submittedAt,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
