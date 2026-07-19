package com.chenmingqiang.wirelesssim.task.api;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * 教学注释：本文件为 api/TrainingConfigRequest.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record TrainingConfigRequest(
        // 校验说明：该输入不能为空。
        @NotNull(message = "最大训练步数不能为空")
        // 校验说明：限制数值的最小值。
        @Min(value = 1, message = "最大训练步数不能小于1")
        // 校验说明：限制数值的最大值。
        @Max(value = 10_000_000, message = "最大训练步数不能大于10000000")
        Integer maxTrainingSteps,

        // 校验说明：该输入不能为空。

        @NotNull(message = "学习率不能为空")
        // 校验说明：限制小数的最小值。
        @DecimalMin(value = "0.0", inclusive = false, message = "学习率必须大于0")
        // 校验说明：限制小数的最大值。
        @DecimalMax(value = "1.0", message = "学习率不能大于1")
        BigDecimal learningRate,

        // 校验说明：该输入不能为空。

        @NotNull(message = "批次大小不能为空")
        // 校验说明：限制数值的最小值。
        @Min(value = 1, message = "批次大小不能小于1")
        // 校验说明：限制数值的最大值。
        @Max(value = 65536, message = "批次大小不能大于65536")
        Integer batchSize,

        // 校验说明：该输入不能为空。

        @NotNull(message = "折扣因子不能为空")
        // 校验说明：限制小数的最小值。
        @DecimalMin(value = "0.0", inclusive = false, message = "折扣因子必须大于0")
        // 校验说明：限制小数的最大值。
        @DecimalMax(value = "1.0", message = "折扣因子不能大于1")
        BigDecimal discountFactor,

        // 校验说明：该输入不能为空。

        @NotNull(message = "随机种子不能为空")
        Long randomSeed
) {
}
