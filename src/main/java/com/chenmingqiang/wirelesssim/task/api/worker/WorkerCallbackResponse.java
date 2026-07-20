package com.chenmingqiang.wirelesssim.task.api.worker;

/** 成功/失败回调的幂等处理结论。 */
public record WorkerCallbackResponse(String outcome, String detail) {
}
