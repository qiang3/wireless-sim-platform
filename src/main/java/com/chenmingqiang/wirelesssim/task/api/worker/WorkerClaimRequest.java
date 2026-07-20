package com.chenmingqiang.wirelesssim.task.api.worker;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Python进程领取任务时上报的稳定实例标识。 */
public record WorkerClaimRequest(
        @NotBlank @Size(max = 100) String workerId
) {
}
