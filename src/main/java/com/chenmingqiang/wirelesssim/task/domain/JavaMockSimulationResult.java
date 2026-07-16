package com.chenmingqiang.wirelesssim.task.domain;

import java.math.BigDecimal;

public record JavaMockSimulationResult(
        BigDecimal throughput,
        BigDecimal averageAoi,
        int convergenceStep,
        long deterministicSeed,
        String simulationMode,
        boolean scientificResult
) {
    public static final String JAVA_MOCK_MODE = "JAVA_MOCK";
}
