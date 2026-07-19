package com.chenmingqiang.wirelesssim.system.api;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 管理员权限测试接口，用于验证Spring Security的角色授权是否生效。
 */
// @RestController表示该类的方法返回值直接序列化为JSON，而不是跳转到页面模板。
@RestController
// @RequestMapping为类内所有接口统一添加URL前缀。
@RequestMapping("/api/v1/admin")
public class AdminController {

    /**
     * 管理员探针；实际访问权限由SecurityConfiguration配置。
     *
     * @return 固定的管理员授权成功标记
     */
    // @GetMapping把HTTP GET /api/v1/admin/ping映射到本方法。
    @GetMapping("/ping")
    public ApiResponse<Map<String, String>> ping() {
        return ApiResponse.success(Map.of("status", "ADMIN_ACCESS_GRANTED"));
    }
}
