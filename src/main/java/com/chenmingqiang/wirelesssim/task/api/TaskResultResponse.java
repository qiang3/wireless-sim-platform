package com.chenmingqiang.wirelesssim.task.api;

import java.math.BigDecimal;
import java.time.LocalDateTime;

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
