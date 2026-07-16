package com.chenmingqiang.wirelesssim.scenario.api;

import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateScenarioRequest(
        @NotBlank(message = "场景名称不能为空")
        @Size(max = 100, message = "场景名称不能超过100个字符")
        String name,

        @Size(max = 500, message = "场景描述不能超过500个字符")
        String description,

        @NotNull(message = "优化目标不能为空")
        ScenarioObjective objective,

        @NotNull(message = "场景配置不能为空")
        @Valid
        ScenarioConfigRequest config
) {
}
