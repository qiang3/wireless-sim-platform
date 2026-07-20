package com.chenmingqiang.wirelesssim.task.domain;

import java.math.BigDecimal;

/** Python预训练GRPO推理的可追溯结果，不冒充Java模拟或在线训练结果。 */
public record GrpoInferenceResult(
        String modelId,
        String checkpointSha256,
        long baseSeed,
        BigDecimal throughputMean,
        BigDecimal throughputStd,
        BigDecimal throughputMin,
        BigDecimal throughputMax,
        int totalTimesteps,
        BigDecimal totalEvaluationTimeSeconds,
        String artifactPath
) {
}
