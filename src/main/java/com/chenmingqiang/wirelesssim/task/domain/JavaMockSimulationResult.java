package com.chenmingqiang.wirelesssim.task.domain;

import java.math.BigDecimal;

/**
 * 教学注释：本文件为 domain/JavaMockSimulationResult.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
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
