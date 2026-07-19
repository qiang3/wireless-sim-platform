package com.chenmingqiang.wirelesssim.task.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 教学注释：本文件为 api/TaskResultResponse.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record TaskResultResponse(
        Long taskId,
        BigDecimal throughput,
        BigDecimal averageAoi,
        Integer convergenceStep,
        Long deterministicSeed,
        String simulationMode,
        Boolean scientificResult,
        String artifactPath,
        LocalDateTime createdAt
) {
}
