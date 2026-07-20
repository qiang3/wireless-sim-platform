package com.chenmingqiang.wirelesssim.task.api.worker;

/** 领取结论与推理输入；只有CLAIMED/RESUMABLE携带完整输入。 */
public record WorkerClaimResponse(
        String outcome,
        String detail,
        Long taskId,
        Integer attemptNo,
        Long executionId,
        String modelId,
        WorkerScenePayload scene,
        WorkerEvaluationPayload evaluation
) {
}
