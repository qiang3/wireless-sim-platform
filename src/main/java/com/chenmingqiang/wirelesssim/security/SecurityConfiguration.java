package com.chenmingqiang.wirelesssim.security;

import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;

/**
 * Spring Security 总配置：定义密码加密方式、JWT 角色转换规则和 HTTP 访问控制规则。
 */
// Spring说明：声明配置类，Spring启动时会读取其中的Bean定义。
@Configuration(proxyBeanMethods = false)
// 开启 @PreAuthorize 等方法级权限注解；当前配置也为后续细粒度鉴权预留能力。
@EnableMethodSecurity
public class SecurityConfiguration {

    // Spring说明：把方法返回的对象注册为Spring Bean。

    @Bean
    /** 使用 BCrypt 单向哈希保存密码；数据库中不保存用户输入的明文密码。 */
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    // Spring说明：把方法返回的对象注册为Spring Bean。

    @Bean
    /** 把 JWT 中 role=USER 转换为 Spring Security 能识别的 ROLE_USER 权限。 */
    JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter authoritiesConverter = new JwtGrantedAuthoritiesConverter();
        authoritiesConverter.setAuthoritiesClaimName("role");
        authoritiesConverter.setAuthorityPrefix("ROLE_");

        JwtAuthenticationConverter authenticationConverter = new JwtAuthenticationConverter();
        authenticationConverter.setJwtGrantedAuthoritiesConverter(authoritiesConverter);
        return authenticationConverter;
    }

    // Spring说明：把方法返回的对象注册为Spring Bean。

    @Bean
    /**
     * 构建安全过滤链。请求进入 Controller 前，会先在这里完成令牌解析、身份认证和权限判断。
     */
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            JwtAuthenticationConverter authenticationConverter,
            SecurityErrorWriter errorWriter,
            WorkerApiAuthenticationFilter workerApiAuthenticationFilter
    ) throws Exception {
        http
                // JWT API 不依赖浏览器 Cookie，会话也不保存在服务端，因此关闭 CSRF。
                .csrf(AbstractHttpConfigurer::disable)
                // 每个请求都携带 Bearer Token，服务端不创建 HttpSession。
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(authorize -> authorize
                        // 注册、登录和健康检查无需令牌，其余接口默认必须登录。
                        .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login").permitAll()
                        .requestMatchers("/api/v1/system/ping", "/actuator/health", "/error").permitAll()
                        // 内部Worker不使用用户JWT，而由前置过滤器校验独立的X-Worker-Token。
                        .requestMatchers("/api/v1/internal/worker/**").permitAll()
                        .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                        .anyRequest().authenticated()
                )
                .addFilterBefore(workerApiAuthenticationFilter, BearerTokenAuthenticationFilter.class)
                // 使用 Spring Security Resource Server 的标准过滤器校验 JWT。
                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt.jwtAuthenticationConverter(authenticationConverter))
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "UNAUTHORIZED",
                                "缺少或使用了无效的访问令牌"
                        ))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                "FORBIDDEN",
                                "当前用户没有访问该资源的权限"
                        ))
                )
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> errorWriter.write(
                                response,
                                HttpServletResponse.SC_UNAUTHORIZED,
                                "UNAUTHORIZED",
                                "请先登录"
                        ))
                        .accessDeniedHandler((request, response, exception) -> errorWriter.write(
                                response,
                                HttpServletResponse.SC_FORBIDDEN,
                                "FORBIDDEN",
                                "当前用户没有访问该资源的权限"
                        ))
                )
                .httpBasic(AbstractHttpConfigurer::disable)
                .formLogin(AbstractHttpConfigurer::disable)
                .logout(Customizer.withDefaults());

        return http.build();
    }
}
