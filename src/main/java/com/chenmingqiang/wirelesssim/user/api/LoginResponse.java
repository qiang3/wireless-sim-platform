package com.chenmingqiang.wirelesssim.user.api;

/**
 * 教学注释：本文件为 api/LoginResponse.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Long userId,
        String username,
        String role
) {
}
