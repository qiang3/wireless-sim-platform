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

@Validated
@RestController
@RequestMapping("/api/v1/tasks")
public class TaskController {

    private final TaskService taskService;
    private final TaskResultService taskResultService;

    public TaskController(TaskService taskService, TaskResultService taskResultService) {
        this.taskService = taskService;
        this.taskResultService = taskResultService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<TaskResponse>> submit(
            JwtAuthenticationToken authentication,
            @RequestHeader(name = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateTaskRequest request
    ) {
        TaskResponse response = taskService.submit(userId(authentication), idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success(response));
    }

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

    @GetMapping("/{id}")
    public ApiResponse<TaskResponse> get(
            JwtAuthenticationToken authentication,
            @PathVariable Long id
    ) {
        return ApiResponse.success(taskService.get(userId(authentication), id));
    }

    @GetMapping("/{id}/result")
    public ApiResponse<TaskResultResponse> getResult(
            JwtAuthenticationToken authentication,
            @PathVariable Long id
    ) {
        return ApiResponse.success(taskResultService.getOwnedResult(userId(authentication), id));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<TaskResponse> cancel(
            JwtAuthenticationToken authentication,
            @PathVariable Long id,
            @Valid @RequestBody TaskActionRequest request
    ) {
        return ApiResponse.success(taskService.cancel(userId(authentication), id, request));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<TaskResponse> retry(
            JwtAuthenticationToken authentication,
            @PathVariable Long id,
            @Valid @RequestBody TaskActionRequest request
    ) {
        return ApiResponse.success(taskService.retry(userId(authentication), id, request));
    }

    private Long userId(JwtAuthenticationToken authentication) {
        Number userId = authentication.getToken().getClaim("user_id");
        return userId.longValue();
    }
}
