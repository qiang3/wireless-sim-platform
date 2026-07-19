package com.chenmingqiang.wirelesssim.task.api;

import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * 教学注释：本文件为 api/CreateTaskRequest.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record CreateTaskRequest(
        // 校验说明：该输入不能为空。
        @NotNull(message = "场景ID不能为空")
        @Positive(message = "场景ID必须大于0")
        Long scenarioId,

        // 校验说明：该输入不能为空。

        @NotNull(message = "算法不能为空")
        TaskAlgorithm algorithm,

        // 校验说明：该输入不能为空。

        @NotNull(message = "训练参数不能为空")
        // 校验说明：递归校验请求对象内部字段。
        @Valid
        TrainingConfigRequest trainingConfig,

        // 校验说明：限制数值的最小值。

        @Min(value = 1, message = "优先级不能小于1")
        // 校验说明：限制数值的最大值。
        @Max(value = 10, message = "优先级不能大于10")
        Integer priority
) {
}
