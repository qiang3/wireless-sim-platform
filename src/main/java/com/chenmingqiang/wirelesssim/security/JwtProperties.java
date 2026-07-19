package com.chenmingqiang.wirelesssim.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

// 配置说明：把application.yml中对应前缀的配置绑定到该类型。

@ConfigurationProperties("app.security.jwt")
/**
 * 教学注释：本文件为 JwtProperties.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 * 配置依赖注入
 */
public record JwtProperties(
        String issuer,
        Duration accessTokenTtl,
        String secretBase64
) {
}
