package com.chenmingqiang.wirelesssim.task.api.worker;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import java.math.BigDecimal;

/** Python推理成功回调；AoI故意不在契约中，Java始终保存NULL。 */
public record WorkerCompleteRequest(
        @NotNull @Min(1) Long executionId,
        @NotBlank String modelId,
        @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String checkpointSha256,
        @NotNull Long baseSeed,
        @NotNull @DecimalMin("0.0") BigDecimal throughputMean,
        @NotNull @DecimalMin("0.0") BigDecimal throughputStd,
        @NotNull @DecimalMin("0.0") BigDecimal throughputMin,
        @NotNull @DecimalMin("0.0") BigDecimal throughputMax,
        @NotNull @Min(1) Integer totalTimesteps,
        @NotNull @DecimalMin("0.0") BigDecimal totalEvaluationTimeSeconds,
        @Size(max = 500) String artifactPath
) {
}
