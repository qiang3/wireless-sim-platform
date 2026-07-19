package com.chenmingqiang.wirelesssim.security;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

// Spring说明：将该类交给Spring容器创建和管理。

@Component
/**
 * 教学注释：本文件为 SecurityErrorWriter.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class SecurityErrorWriter {

    /** 字段说明：`objectMapper`保存该对象运行所需的依赖、配置或状态。 */
    private final ObjectMapper objectMapper;

    /** 方法说明：`SecurityErrorWriter`封装下面这段业务或转换逻辑。 */
    public SecurityErrorWriter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void write(HttpServletResponse response, int status, String code, String message) throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding("UTF-8");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        objectMapper.writeValue(response.getOutputStream(), ApiResponse.error(code, message));
    }
}
