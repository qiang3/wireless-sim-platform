package com.chenmingqiang.wirelesssim.security;

import com.chenmingqiang.wirelesssim.user.domain.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.stereotype.Service;

/**
 * JWT 签发服务：登录成功后把用户身份、角色和有效期编码成带签名的访问令牌。
 */
// Spring说明：将该类注册为业务服务Bean，其他组件可通过构造方法注入它。
@Service
public class JwtTokenService {

    /** Spring 提供的 JWT 编码器，负责使用密钥生成不可篡改的签名。 */
    private final JwtEncoder encoder;
    /** application.yml 映射出的签发者、密钥和令牌有效期配置。 */
    private final JwtProperties properties;
    /** 获取当前时间；抽成依赖后，单元测试可以注入固定时钟。 */
    private final Clock clock;

    @Autowired
    /** 生产环境构造方法，默认使用 UTC 系统时钟。 */
    public JwtTokenService(JwtEncoder encoder, JwtProperties properties) {
        this(encoder, properties, Clock.systemUTC());
    }

    /** 测试专用构造方法，允许传入固定时钟以稳定断言签发时间和过期时间。 */
    JwtTokenService(JwtEncoder encoder, JwtProperties properties, Clock clock) {
        this.encoder = encoder;
        this.properties = properties;
        this.clock = clock;
    }

    /** 为已通过用户名密码校验的用户签发访问令牌，并返回令牌值及剩余秒数。 */
    public IssuedToken issue(UserAccount user) {
        Instant issuedAt = clock.instant(); // iat：令牌签发时间。
        Instant expiresAt = issuedAt.plus(properties.accessTokenTtl()); // exp：令牌过期时间。
        JwsHeader header = JwsHeader.with(MacAlgorithm.HS256)
                .type("JWT")
                .build();
        // Claims 是令牌携带的身份数据；签名可防篡改，但内容并非加密，不能放密码等秘密。
        JwtClaimsSet claims = JwtClaimsSet.builder()
                .issuer(properties.issuer())
                .subject(user.getUsername())
                .issuedAt(issuedAt)
                .expiresAt(expiresAt)
                .id(UUID.randomUUID().toString())
                .claim("user_id", user.getId())
                .claim("role", user.getRole().name())
                .build();

        String tokenValue = encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
        return new IssuedToken(tokenValue, properties.accessTokenTtl().toSeconds());
    }

    public record IssuedToken(String value, long expiresInSeconds) {
    }
}
