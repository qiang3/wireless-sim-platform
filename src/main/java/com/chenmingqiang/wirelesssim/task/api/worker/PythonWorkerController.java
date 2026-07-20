package com.chenmingqiang.wirelesssim.task.api.worker;

import com.chenmingqiang.wirelesssim.common.api.ApiResponse;
import com.chenmingqiang.wirelesssim.task.application.PythonWorkerService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Python Worker专用内部API：仅保留领取、成功和失败三个短请求。 */
@Validated
@RestController
@RequestMapping("/api/v1/internal/worker/tasks/{taskId}/attempts/{attemptNo}")
public class PythonWorkerController {

    private final PythonWorkerService workerService;

    public PythonWorkerController(PythonWorkerService workerService) {
        this.workerService = workerService;
    }

    /** 领取任务并返回不可变场景快照；重复的RUNNING轮次返回RESUMABLE。 */
    @PostMapping("/claim")
    public ApiResponse<WorkerClaimResponse> claim(
            @PathVariable @Min(1) long taskId,
            @PathVariable @Min(1) int attemptNo,
            @Valid @RequestBody WorkerClaimRequest request
    ) {
        return ApiResponse.success(workerService.claim(taskId, attemptNo, request.workerId()));
    }

    /** 接收标准推理结果，由Java在单个数据库事务内关闭任务。 */
    @PostMapping("/complete")
    public ApiResponse<WorkerCallbackResponse> complete(
            @PathVariable @Min(1) long taskId,
            @PathVariable @Min(1) int attemptNo,
            @Valid @RequestBody WorkerCompleteRequest request
    ) {
        return ApiResponse.success(workerService.complete(taskId, attemptNo, request));
    }

    /** 接收分类错误并关闭本次执行，避免消息无限重投。 */
    @PostMapping("/fail")
    public ApiResponse<WorkerCallbackResponse> fail(
            @PathVariable @Min(1) long taskId,
            @PathVariable @Min(1) int attemptNo,
            @Valid @RequestBody WorkerFailRequest request
    ) {
        return ApiResponse.success(workerService.fail(taskId, attemptNo, request));
    }
}
