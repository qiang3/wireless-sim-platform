package com.chenmingqiang.wirelesssim.task.domain;

public record SimulationMetrics(
        long deterministicSeed,
        String simulationMode,
        boolean scientificResult
) {
}
