package com.chenmingqiang.wirelesssim.security;

import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.util.StringUtils;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(JwtProperties.class)
public class JwtConfiguration {

    private static final Logger log = LoggerFactory.getLogger(JwtConfiguration.class);
    private static final int HS256_MINIMUM_KEY_BYTES = 32;

    @Bean
    SecretKey jwtSecretKey(JwtProperties properties) {
        byte[] keyBytes;
        if (StringUtils.hasText(properties.secretBase64())) {
            try {
                keyBytes = Base64.getDecoder().decode(properties.secretBase64());
            } catch (IllegalArgumentException exception) {
                throw new IllegalStateException("JWT_SECRET_BASE64必须是合法的Base64字符串", exception);
            }
            if (keyBytes.length < HS256_MINIMUM_KEY_BYTES) {
                throw new IllegalStateException("JWT密钥解码后不能少于32字节");
            }
        } else {
            keyBytes = new byte[HS256_MINIMUM_KEY_BYTES];
            new SecureRandom().nextBytes(keyBytes);
            log.warn("未配置JWT_SECRET_BASE64，本次启动使用随机开发密钥；应用重启后旧Token将失效");
        }
        return new SecretKeySpec(keyBytes, "HmacSHA256");
    }

    @Bean
    JwtEncoder jwtEncoder(SecretKey secretKey) {
        return NimbusJwtEncoder.withSecretKey(secretKey)
                .algorithm(MacAlgorithm.HS256)
                .build();
    }

    @Bean
    JwtDecoder jwtDecoder(SecretKey secretKey, JwtProperties properties) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        decoder.setJwtValidator(JwtValidators.createDefaultWithIssuer(properties.issuer()));
        return decoder;
    }
}
