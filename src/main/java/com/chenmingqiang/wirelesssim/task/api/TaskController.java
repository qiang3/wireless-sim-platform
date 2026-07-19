package com.chenmingqiang.wirelesssim.task.api;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import com.chenmingqiang.wirelesssim.common.api.PageResponse;
import com.chenmingqiang.wirelesssim.task.application.TaskService;
import com.chenmingqiang.wirelesssim.task.application.TaskResultService;
import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// 校验说明：启用该类型上的Bean Validation参数约束。

@Validated
// Spring MVC说明：声明REST控制器，方法返回值会被序列化为JSON。
@RestController
// Spring MVC说明：为控制器或方法设置统一请求路径。
@RequestMapping("/api/v1/tasks")
/**
 * 教学注释：本文件为 api/TaskController.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public class TaskController {

    /** 字段说明：`taskService`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskService taskService;
    /** 字段说明：`taskResultService`保存该对象运行所需的依赖、配置或状态。 */
    private final TaskResultService taskResultService;

    /** 方法说明：`TaskController`封装下面这段业务或转换逻辑。 */
    public TaskController(TaskService taskService, TaskResultService taskResultService) {
        this.taskService = taskService;
        this.taskResultService = taskResultService;
    }

    // Spring MVC说明：将HTTP POST请求映射到下面的方法。

    @PostMapping
    public ResponseEntity<ApiResponse<TaskResponse>> submit(
            JwtAuthenticationToken authentication,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            // 校验说明：递归校验请求对象内部字段。
            @Valid @RequestBody CreateTaskRequest request
    ) {
        TaskResponse response = taskService.submit(userId(authentication), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

    // Spring MVC说明：将HTTP GET请求映射到下面的方法。

    @GetMapping
    public ApiResponse<PageResponse<TaskResponse>> list(
            JwtAuthenticationToken authentication,
            @RequestParam(required = false) TaskStatus status,
            @RequestParam(required = false) TaskAlgorithm algorithm,
            @RequestParam(defaultValue = "0") @Min(0) @Max(1_000_000) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size
    ) {
        return ApiResponse.success(taskService.list(userId(authentication), status, algorithm, page, size));
    }

    // Spring MVC说明：将HTTP GET请求映射到下面的方法。

    @GetMapping("/{id}")
    public ApiResponse<TaskResponse> get(
            JwtAuthenticationToken authentication,
            @PathVariable Long id
    ) {
        return ApiResponse.success(taskService.get(userId(authentication), id));
    }

    // Spring MVC说明：将HTTP GET请求映射到下面的方法。

    @GetMapping("/{id}/result")
    public ApiResponse<TaskResultResponse> getResult(
            JwtAuthenticationToken authentication,
            @PathVariable Long id
    ) {
        return ApiResponse.success(taskResultService.getOwnedResult(userId(authentication), id));
    }

    // Spring MVC说明：将HTTP POST请求映射到下面的方法。

    @PostMapping("/{id}/cancel")
    public ApiResponse<TaskResponse> cancel(
            JwtAuthenticationToken authentication,
            @PathVariable Long id,
            // 校验说明：递归校验请求对象内部字段。
            @Valid @RequestBody TaskActionRequest request
    ) {
        return ApiResponse.success(taskService.cancel(userId(authentication), id, request));
    }

    // Spring MVC说明：将HTTP POST请求映射到下面的方法。

    @PostMapping("/{id}/retry")
    public ApiResponse<TaskResponse> retry(
            JwtAuthenticationToken authentication,
            @PathVariable Long id,
            // 校验说明：递归校验请求对象内部字段。
            @Valid @RequestBody TaskActionRequest request
    ) {
        return ApiResponse.success(taskService.retry(userId(authentication), id, request));
    }

    /** 方法说明：`userId`封装下面这段业务或转换逻辑。 */
    private Long userId(JwtAuthenticationToken authentication) {
        Number userId = authentication.getToken().getClaim("user_id");
        return userId.longValue();
    }
}
