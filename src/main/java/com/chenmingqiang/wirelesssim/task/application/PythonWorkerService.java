package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;
import com.chenmingqiang.wirelesssim.task.api.ScenarioSnapshot;
import com.chenmingqiang.wirelesssim.task.api.worker.WorkerCallbackResponse;
import com.chenmingqiang.wirelesssim.task.api.worker.WorkerClaimResponse;
import com.chenmingqiang.wirelesssim.task.api.worker.WorkerCompleteRequest;
import com.chenmingqiang.wirelesssim.task.api.worker.WorkerEvaluationPayload;
import com.chenmingqiang.wirelesssim.task.api.worker.WorkerFailRequest;
import com.chenmingqiang.wirelesssim.task.api.worker.WorkerScenePayload;
import com.chenmingqiang.wirelesssim.task.domain.ExecutionStatus;
import com.chenmingqiang.wirelesssim.task.domain.GrpoInferenceResult;
import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.domain.TaskExecution;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskExecutionMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.worker.WorkerApiProperties;
import java.util.Optional;
import org.springframework.stereotype.Service;
import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import org.springframework.http.HttpStatus;

/** Java与Python之间的应用服务：校验模型边界、抢占任务并接收终态回调。 */
@Service
public class PythonWorkerService {

    private final TaskMessagePreparationService preparationService;
    private final TaskExecutionClaimService claimService;
    private final TaskExecutionRuntimeService runtimeService;
    private final TaskExecutionLifecycleService lifecycleService;
    private final TaskExecutionMapper executionMapper;
    private final WorkerApiProperties properties;
    private final TaskMessageFailureService failureService;

    public PythonWorkerService(
            TaskMessagePreparationService preparationService,
            TaskExecutionClaimService claimService,
            TaskExecutionRuntimeService runtimeService,
            TaskExecutionLifecycleService lifecycleService,
            TaskExecutionMapper executionMapper,
            WorkerApiProperties properties,
            TaskMessageFailureService failureService
    ) {
        this.preparationService = preparationService;
        this.claimService = claimService;
        this.runtimeService = runtimeService;
        this.lifecycleService = lifecycleService;
        this.executionMapper = executionMapper;
        this.properties = properties;
        this.failureService = failureService;
    }

    /** 领取或恢复同一业务轮次；重复消息不会创建第二条执行记录。 */
    public WorkerClaimResponse claim(long taskId, int attemptNo, String workerId) {
        SimulationExecutionContext context;
        try {
            context = runtimeService.loadContext(taskId);
        } catch (IllegalStateException exception) {
            return empty("REJECTED", "任务不存在或快照无法读取：" + exception.getMessage(), taskId, attemptNo);
        }
        String incompatibility = validateCompatibility(context);
        if (incompatibility != null) {
            failureService.markPermanentlyRejected(taskId, attemptNo, incompatibility);
            return empty("REJECTED", incompatibility, taskId, attemptNo);
        }

        TaskMessagePreparationResult preparation = preparationService.prepare(taskId, attemptNo);
        switch (preparation.outcome()) {
            case TASK_NOT_FOUND, FUTURE_ATTEMPT -> {
                return empty("REJECTED", preparation.detail(), taskId, attemptNo);
            }
            case STALE_ATTEMPT -> {
                return empty("STALE_ATTEMPT", preparation.detail(), taskId, attemptNo);
            }
            case ALREADY_HANDLED -> {
                TaskExecution existing = executionMapper.findByTaskIdAndAttemptNo(taskId, attemptNo);
                if (existing != null && existing.getStatus() == ExecutionStatus.RUNNING
                        && runtimeService.findTaskStatus(taskId) == TaskStatus.RUNNING) {
                    return payload("RESUMABLE", "相同轮次已领取，可安全重新执行并幂等回调", existing, context);
                }
                return empty("ALREADY_HANDLED", preparation.detail(), taskId, attemptNo);
            }
            case READY_TO_EXECUTE -> {
                Optional<TaskExecution> claimed = claimService.claimQueuedTask(taskId, attemptNo, workerId);
                if (claimed.isPresent()) {
                    return payload("CLAIMED", "任务领取成功", claimed.get(), context);
                }
                TaskExecution existing = executionMapper.findByTaskIdAndAttemptNo(taskId, attemptNo);
                if (existing != null && existing.getStatus() == ExecutionStatus.RUNNING) {
                    return payload("RESUMABLE", "并发领取已由同轮次吸收", existing, context);
                }
                return empty("ALREADY_HANDLED", "任务已由其他流程处理", taskId, attemptNo);
            }
        }
        throw new IllegalStateException("未处理的领取结论：" + preparation.outcome());
    }

    /** 成功回调具有条件更新幂等性：只有RUNNING能首次进入SUCCEEDED。 */
    public WorkerCallbackResponse complete(long taskId, int attemptNo, WorkerCompleteRequest request) {
        TaskExecution execution = requireMatchingExecution(taskId, attemptNo, request.executionId());
        if (!properties.modelId().equals(request.modelId())) {
            throw new BusinessException(HttpStatus.CONFLICT, "MODEL_ID_MISMATCH", "回调modelId与服务端配置不一致");
        }
        if (execution.getStatus() != ExecutionStatus.RUNNING) {
            return new WorkerCallbackResponse("ALREADY_HANDLED", "该执行轮次已经进入终态");
        }
        boolean completed = lifecycleService.completeGrpoInference(
                taskId,
                request.executionId(),
                new GrpoInferenceResult(
                        request.modelId(), request.checkpointSha256(), request.baseSeed(),
                        request.throughputMean(), request.throughputStd(), request.throughputMin(),
                        request.throughputMax(), request.totalTimesteps(),
                        request.totalEvaluationTimeSeconds(), request.artifactPath()
                )
        );
        return completed
                ? new WorkerCallbackResponse("COMPLETED", "GRPO评估结果已事务化保存")
                : new WorkerCallbackResponse("ALREADY_HANDLED", "任务已由其他回调完成");
    }

    /** 失败回调把错误码与说明写入同一个受长度约束的错误字段。 */
    public WorkerCallbackResponse fail(long taskId, int attemptNo, WorkerFailRequest request) {
        TaskExecution execution = requireMatchingExecution(taskId, attemptNo, request.executionId());
        if (execution.getStatus() != ExecutionStatus.RUNNING) {
            return new WorkerCallbackResponse("ALREADY_HANDLED", "该执行轮次已经进入终态");
        }
        lifecycleService.failRunningExecution(
                taskId,
                request.executionId(),
                request.errorCode() + ": " + request.errorMessage()
        );
        return new WorkerCallbackResponse("FAILED", "任务失败状态已保存");
    }

    private TaskExecution requireMatchingExecution(long taskId, int attemptNo, long executionId) {
        TaskExecution execution = executionMapper.findByTaskIdAndAttemptNo(taskId, attemptNo);
        if (execution == null || execution.getId() != executionId) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "EXECUTION_MISMATCH",
                    "executionId与任务执行轮次不匹配"
            );
        }
        return execution;
    }

    private String validateCompatibility(SimulationExecutionContext context) {
        ScenarioSnapshot snapshot = context.scenarioSnapshot();
        ScenarioConfigRequest config = snapshot.config();
        if (context.task().getAlgorithm() != TaskAlgorithm.GRPO) return "第一版Python Worker只支持GRPO";
        if (snapshot.objective() != ScenarioObjective.THROUGHPUT) return "第一版只支持吞吐量目标";
        if (config.accessScheme() != AccessScheme.RSMA) return "第一版只支持RSMA";
        if (config.deviceCount() != 3) return "当前权重只支持3个设备";
        return null;
    }

    private WorkerClaimResponse payload(
            String outcome, String detail, TaskExecution execution, SimulationExecutionContext context
    ) {
        ScenarioConfigRequest config = context.scenarioSnapshot().config();
        return new WorkerClaimResponse(
                outcome, detail, context.task().getId(), execution.getAttemptNo(), execution.getId(),
                properties.modelId(),
                new WorkerScenePayload(
                        config.accessScheme().name(), config.deviceCount(), config.antennaCount(), false,
                        config.timeSlotCount(), config.dataArrivalRate(), config.averageGreenEnergy(),
                        config.batteryCapacity(), config.dataBufferCapacity(), config.wptTransmitPower(),
                        config.deviceMaxTransmitPower()
                ),
                new WorkerEvaluationPayload(
                        "PRETRAINED_MODEL", true, context.trainingConfig().randomSeed(),
                        properties.evaluationEpisodes(), config.timeSlotCount()
                )
        );
    }

    private WorkerClaimResponse empty(String outcome, String detail, long taskId, int attemptNo) {
        return new WorkerClaimResponse(outcome, detail, taskId, attemptNo, null, null, null, null);
    }
}
