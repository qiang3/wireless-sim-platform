package com.chenmingqiang.wirelesssim.user.api;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import com.chenmingqiang.wirelesssim.user.application.AuthenticationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Spring MVC说明：声明REST控制器，方法返回值会被序列化为JSON。

@RestController
// Spring MVC说明：为控制器或方法设置统一请求路径。
@RequestMapping("/api/v1/auth")
/**
 * 教学注释：本文件为 api/AuthenticationController.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class AuthenticationController {

    /** 字段说明：`authenticationService`保存该对象运行所需的依赖、配置或状态。 */
    private final AuthenticationService authenticationService;

    /** 方法说明：`AuthenticationController`封装下面这段业务或转换逻辑。 */
    public AuthenticationController(AuthenticationService authenticationService) {
        this.authenticationService = authenticationService;
    }

    // Spring MVC说明：将HTTP POST请求映射到下面的方法。

    @PostMapping("/register")
    /** 方法说明：`register`封装下面这段业务或转换逻辑。 */
    public ResponseEntity<ApiResponse<RegisterResponse>> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(authenticationService.register(request)));
    }

    // Spring MVC说明：将HTTP POST请求映射到下面的方法。

    @PostMapping("/login")
    /** 方法说明：`login`封装下面这段业务或转换逻辑。 */
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authenticationService.login(request));
    }
}
