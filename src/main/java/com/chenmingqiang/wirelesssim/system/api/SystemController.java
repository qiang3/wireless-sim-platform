package com.chenmingqiang.wirelesssim.system.api;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 无需业务参数的系统级接口，主要用于人工检查服务是否启动。
 */
// @RestController让方法返回对象自动经过Jackson转换为JSON。
@RestController
// 为本类全部接口设置统一前缀/api/v1/system。
@RequestMapping("/api/v1/system")
public class SystemController {

    /**
     * 返回当前服务存活信息。
     *
     * @return 服务名、UP状态和每次请求时动态生成的UTC时间
     */
    // @GetMapping声明这是一个只读GET接口。
    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        // Map.of创建不可修改的小型Map，适合固定健康信息。
        return ApiResponse.success(Map.of(
                "service", "wireless-sim-platform",
                "status", "UP",
                "timestamp", Instant.now().toString()
        ));
    }
}
