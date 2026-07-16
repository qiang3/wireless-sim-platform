package com.chenmingqiang.wirelesssim.system.api;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import java.time.Instant;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/system")
public class SystemController {

    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        return ApiResponse.success(Map.of(
                "service", "wireless-sim-platform",
                "status", "UP",
                "timestamp", Instant.now().toString()
        ));
    }
}

