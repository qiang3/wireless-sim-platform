package com.chenmingqiang.wirelesssim.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.jwt")
public record JwtProperties(
        String issuer,
        Duration accessTokenTtl,
        String secretBase64
) {
}
