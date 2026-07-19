package com.chenmingqiang.wirelesssim.scenario.api;

import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * 教学注释：本文件为 api/UpdateScenarioRequest.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record UpdateScenarioRequest(
        // 校验说明：该输入不能为空。
        @NotNull(message = "版本号不能为空")
        @PositiveOrZero(message = "版本号不能小于0")
        Integer version,

        @NotBlank(message = "场景名称不能为空")
        @Size(max = 100, message = "场景名称不能超过100个字符")
        String name,

        @Size(max = 500, message = "场景描述不能超过500个字符")
        String description,

        // 校验说明：该输入不能为空。

        @NotNull(message = "优化目标不能为空")
        ScenarioObjective objective,

        // 校验说明：该输入不能为空。

        @NotNull(message = "场景配置不能为空")
        // 校验说明：递归校验请求对象内部字段。
        @Valid
        ScenarioConfigRequest config
) {
}
