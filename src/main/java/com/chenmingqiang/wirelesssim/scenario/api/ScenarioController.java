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

// 校验说明：启用该类型上的Bean Validation参数约束。

@Validated
// Spring MVC说明：声明REST控制器，方法返回值会被序列化为JSON。
@RestController
// Spring MVC说明：为控制器或方法设置统一请求路径。
/**
 * 场景 REST 接口：只负责接收并校验 HTTP 参数、提取登录用户，再委托应用服务处理业务。
 */
@RequestMapping("/api/v1/scenarios")
public class ScenarioController {

    /** 场景业务用例入口，通过构造器注入。 */
    private final ScenarioService scenarioService;

    /** 方法说明：`ScenarioController`封装下面这段业务或转换逻辑。 */
    public ScenarioController(ScenarioService scenarioService) {
        this.scenarioService = scenarioService;
    }

    // Spring MVC说明：将HTTP POST请求映射到下面的方法。

    @PostMapping
    /** 创建场景，成功时返回 HTTP 201 和新场景数据。 */
    public ResponseEntity<ApiResponse<ScenarioResponse>> create(
            JwtAuthenticationToken authentication,
            // 校验说明：递归校验请求对象内部字段。
            @Valid @RequestBody CreateScenarioRequest request
    ) {
        ScenarioResponse response = scenarioService.create(userId(authentication), request);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
    }

    // Spring MVC说明：将HTTP GET请求映射到下面的方法。

    @GetMapping
    /** 分页列出当前用户的场景；page 从 0 开始，size 最大为 100。 */
    public ApiResponse<PageResponse<ScenarioResponse>> list(
            JwtAuthenticationToken authentication,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(scenarioService.list(userId(authentication), page, size));
    }

    // Spring MVC说明：将HTTP GET请求映射到下面的方法。

    @GetMapping("/{id}")
    /** 查询指定场景，Service 会同时校验该场景是否属于当前用户。 */
    public ApiResponse<ScenarioResponse> get(
            JwtAuthenticationToken authentication,
            @PathVariable Long id
    ) {
        return ApiResponse.success(scenarioService.get(userId(authentication), id));
    }

    // Spring MVC说明：将HTTP PUT请求映射到下面的方法。

    @PutMapping("/{id}")
    /** 全量更新指定场景，请求中的 version 用于检测并发修改。 */
    public ApiResponse<ScenarioResponse> update(
            JwtAuthenticationToken authentication,
            @PathVariable Long id,
            // 校验说明：递归校验请求对象内部字段。
            @Valid @RequestBody UpdateScenarioRequest request
    ) {
        return ApiResponse.success(scenarioService.update(userId(authentication), id, request));
    }

    // Spring MVC说明：将HTTP DELETE请求映射到下面的方法。

    @DeleteMapping("/{id}")
    /** 归档指定场景，成功后返回 HTTP 204，不返回响应体。 */
    public ResponseEntity<Void> archive(
            JwtAuthenticationToken authentication,
            @PathVariable Long id
    ) {
        scenarioService.archive(userId(authentication), id);
        return ResponseEntity.noContent().build();
    }

    /** 从已经验签的 JWT Claim 中取得用户主键；该值不能由请求参数伪造。 */
    private Long userId(JwtAuthenticationToken authentication) {
        Number userId = authentication.getToken().getClaim("user_id");
        return userId.longValue();
    }
}
