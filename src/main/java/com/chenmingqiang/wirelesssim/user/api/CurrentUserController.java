package com.chenmingqiang.wirelesssim.user.api;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Spring MVC说明：声明REST控制器，方法返回值会被序列化为JSON。

@RestController
// Spring MVC说明：为控制器或方法设置统一请求路径。
@RequestMapping("/api/v1/users")
/**
 * 教学注释：本文件为 api/CurrentUserController.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class CurrentUserController {

    // Spring MVC说明：将HTTP GET请求映射到下面的方法。

    @GetMapping("/me")
    /** 方法说明：`me`封装下面这段业务或转换逻辑。 */
    public ApiResponse<Map<String, Object>> me(JwtAuthenticationToken authentication) {
        return ApiResponse.success(Map.of(
                "id", authentication.getToken().getClaim("user_id"),
                "username", authentication.getName(),
                "role", authentication.getToken().getClaimAsString("role")
        ));
    }
}
