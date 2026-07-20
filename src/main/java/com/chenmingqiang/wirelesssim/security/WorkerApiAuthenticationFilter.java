package com.chenmingqiang.wirelesssim.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.beans.factory.annotation.Value;
import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import org.springframework.http.MediaType;
import tools.jackson.databind.ObjectMapper;

/** 只保护内部Worker接口的静态令牌过滤器，避免把用户JWT交给独立Python进程。 */
@Component
public class WorkerApiAuthenticationFilter extends OncePerRequestFilter {

    public static final String TOKEN_HEADER = "X-Worker-Token";
    private static final String INTERNAL_PATH_PREFIX = "/api/v1/internal/worker/";

    private final String expectedToken;
    private final ObjectMapper objectMapper;

    public WorkerApiAuthenticationFilter(
            @Value("${simulation.worker-api.token:}") String expectedToken,
            ObjectMapper objectMapper
    ) {
        this.expectedToken = expectedToken;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(INTERNAL_PATH_PREFIX);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String actual = request.getHeader(TOKEN_HEADER);
        if (expectedToken == null || expectedToken.isBlank()) {
            writeError(response, 503, "WORKER_API_DISABLED", "未配置内部Worker令牌");
            return;
        }
        if (actual == null || !MessageDigest.isEqual(
                expectedToken.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8)
        )) {
            writeError(response, 401, "INVALID_WORKER_TOKEN", "内部Worker令牌无效");
            return;
        }
        filterChain.doFilter(request, response);
    }

    /** 过滤器发生在Controller之前，因此直接写统一ApiResponse错误结构。 */
    private void writeError(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
