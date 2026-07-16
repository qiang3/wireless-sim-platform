package com.chenmingqiang.wirelesssim.scenario.api;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import com.chenmingqiang.wirelesssim.common.api.PageResponse;
import com.chenmingqiang.wirelesssim.scenario.application.ScenarioService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    private final ScenarioService scenarioService;

    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ScenarioResponse>> create(
            JwtAuthenticationToken authentication,
            @Valid @RequestBody CreateScenarioRequest request
    ) {
        ScenarioResponse response = scenarioService.create(userId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    @GetMapping
    public ApiResponse<PageResponse<ScenarioResponse>> list(
            JwtAuthenticationToken authentication,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(scenarioService.list(userId(authentication), page, size));
    }

    @GetMapping("/{id}")
    public ApiResponse<ScenarioResponse> get(
            JwtAuthenticationToken authentication,
            @PathVariable Long id
    ) {
        return ApiResponse.success(scenarioService.get(userId(authentication), id));
    }

    @PutMapping("/{id}")
    public ApiResponse<ScenarioResponse> update(
            JwtAuthenticationToken authentication,
            @PathVariable Long id,
            @Valid @RequestBody UpdateScenarioRequest request
    ) {
        return ApiResponse.success(scenarioService.update(userId(authentication), id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> archive(
            JwtAuthenticationToken authentication,
            @PathVariable Long id
    ) {
        scenarioService.archive(userId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    private Long userId(JwtAuthenticationToken authentication) {
        Number userId = authentication.getToken().getClaim("user_id");
        return userId.longValue();
    }
}
