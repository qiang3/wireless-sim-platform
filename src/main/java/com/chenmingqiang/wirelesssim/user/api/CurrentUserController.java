package com.chenmingqiang.wirelesssim.user.api;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import java.util.Map;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/users")
public class CurrentUserController {

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(JwtAuthenticationToken authentication) {
        return ApiResponse.success(Map.of(
                "id", authentication.getToken().getClaim("user_id"),
                "username", authentication.getName(),
                "role", authentication.getToken().getClaimAsString("role")
        ));
    }
}
