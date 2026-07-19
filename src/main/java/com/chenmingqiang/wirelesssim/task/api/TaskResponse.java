package com.chenmingqiang.wirelesssim.task.api;

import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import java.time.LocalDateTime;

/**
 * 教学注释：本文件为 api/TaskResponse.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
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
