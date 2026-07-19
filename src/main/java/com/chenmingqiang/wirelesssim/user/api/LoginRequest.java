package com.chenmingqiang.wirelesssim.user.api;

import jakarta.validation.constraints.NotBlank;

/**
 * 教学注释：本文件为 api/LoginRequest.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record LoginRequest(
        @NotBlank(message = "用户名不能为空") String username,
        @NotBlank(message = "密码不能为空") String password
) {
}
