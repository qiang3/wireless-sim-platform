package com.chenmingqiang.wirelesssim.task.api;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 教学注释：本文件为 api/TaskActionRequest.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record TaskActionRequest(
        // 校验说明：该输入不能为空。
        @NotNull(message = "版本号不能为空")
        @PositiveOrZero(message = "版本号不能小于0")
        Integer version
) {
}
