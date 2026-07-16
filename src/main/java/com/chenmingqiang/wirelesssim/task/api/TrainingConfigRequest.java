package com.chenmingqiang.wirelesssim.task.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

public record TrainingConfigRequest(
        @NotNull(message = "最大训练步数不能为空")
        @Min(value = 1, message = "最大训练步数不能小于1")
        @Max(value = 10_000_000, message = "最大训练步数不能大于10000000")
        Integer maxTrainingSteps,

        @NotNull(message = "学习率不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "学习率必须大于0")
        @DecimalMax(value = "1.0", message = "学习率不能大于1")
        BigDecimal learningRate,

        @NotNull(message = "批次大小不能为空")
        @Min(value = 1, message = "批次大小不能小于1")
        @Max(value = 65536, message = "批次大小不能大于65536")
        Integer batchSize,

        @NotNull(message = "折扣因子不能为空")
        @DecimalMin(value = "0.0", inclusive = false, message = "折扣因子必须大于0")
        @DecimalMax(value = "1.0", message = "折扣因子不能大于1")
        BigDecimal discountFactor,

        @NotNull(message = "随机种子不能为空")
        Long randomSeed
) {
}
