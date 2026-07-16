package com.chenmingqiang.wirelesssim.user.api;

public record LoginResponse(
        String accessToken,
        String tokenType,
        long expiresIn,
        Long userId,
        String username,
        String role
) {
}
