package com.chenmingqiang.wirelesssim.task.api.worker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;

/** Python推理失败回调，错误码便于区分兼容性、权重和运行时故障。 */
public record WorkerFailRequest(
        @NotNull @Min(1) Long executionId,
        @NotBlank @Size(max = 80) String errorCode,
        @NotBlank @Size(max = 900) String errorMessage
) {
}
