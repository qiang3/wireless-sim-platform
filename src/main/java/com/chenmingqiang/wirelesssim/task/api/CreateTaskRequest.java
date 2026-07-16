package com.chenmingqiang.wirelesssim.task.api;

import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateTaskRequest(
        @NotNull(message = "场景ID不能为空")
        @Positive(message = "场景ID必须大于0")
        Long scenarioId,

        @NotNull(message = "算法不能为空")
        TaskAlgorithm algorithm,

        @NotNull(message = "训练参数不能为空")
        @Valid
        TrainingConfigRequest trainingConfig,

        @Min(value = 1, message = "优先级不能小于1")
        @Max(value = 10, message = "优先级不能大于10")
        Integer priority
) {
}
