package com.chenmingqiang.wirelesssim.task.api.worker;

/** 预训练模型评估参数；本阶段不包含任何训练超参数。 */
public record WorkerEvaluationPayload(
        String mode,
        boolean deterministic,
        long baseSeed,
        int numEpisodes,
        int maxEpisodeLength
) {
}
