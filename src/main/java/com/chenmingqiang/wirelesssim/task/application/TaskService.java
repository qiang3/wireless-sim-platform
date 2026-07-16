package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.common.api.PageResponse;
import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.SimulationScenario;
import com.chenmingqiang.wirelesssim.scenario.infrastructure.ScenarioMapper;
import com.chenmingqiang.wirelesssim.task.api.CreateTaskRequest;
import com.chenmingqiang.wirelesssim.task.api.ScenarioSnapshot;
import com.chenmingqiang.wirelesssim.task.api.TaskActionRequest;
import com.chenmingqiang.wirelesssim.task.api.TaskResponse;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;
import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.TaskMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class TaskService {

    // 优先级未显式提交时使用中间值，避免默认任务被误判为最高或最低优先级。
    private static final int DEFAULT_PRIORITY = 5;
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    private final TaskMapper taskMapper;
    private final ScenarioMapper scenarioMapper;
    private final ObjectMapper objectMapper;

    public TaskService(TaskMapper taskMapper, ScenarioMapper scenarioMapper, ObjectMapper objectMapper) {
        this.taskMapper = taskMapper;
        this.scenarioMapper = scenarioMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public TaskResponse submit(Long creatorId, String rawIdempotencyKey, CreateTaskRequest request) {
        String idempotencyKey = normalizeIdempotencyKey(rawIdempotencyKey);
        int priority = request.priority() == null ? DEFAULT_PRIORITY : request.priority();
        String requestHash = requestHash(request, priority);

        ExperimentTask existing = taskMapper.findByCreatorAndIdempotencyKey(creatorId, idempotencyKey);
        if (existing != null) {
            return sameRequestOrThrow(existing, requestHash);
        }

        SimulationScenario scenario = scenarioMapper.findActiveOwnedById(request.scenarioId(), creatorId);
        if (scenario == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "SCENARIO_NOT_FOUND", "场景不存在");
        }

        ScenarioSnapshot snapshot = new ScenarioSnapshot(
                scenario.getName(),
                scenario.getDescription(),
                scenario.getObjective(),
                readJson(scenario.getConfigJson(), ScenarioConfigRequest.class, "场景配置无法解析"),
                scenario.getVersion()
        );

        ExperimentTask task = new ExperimentTask();
        task.setTaskNo(UUID.randomUUID().toString().replace("-", ""));
        task.setScenarioId(scenario.getId());
        task.setScenarioSnapshotJson(writeJson(snapshot, "场景快照序列化失败"));
        task.setCreatorId(creatorId);
        task.setAlgorithm(request.algorithm());
        task.setTrainingConfigJson(writeJson(request.trainingConfig(), "训练参数序列化失败"));
        task.setPriority(priority);
        task.setStatus(TaskStatus.PENDING);
        task.setIdempotencyKey(idempotencyKey);
        task.setRequestHash(requestHash);

        try {
            taskMapper.insert(task);
        } catch (DuplicateKeyException exception) {
            ExperimentTask concurrent = taskMapper.findByCreatorAndIdempotencyKey(creatorId, idempotencyKey);
            if (concurrent != null) {
                return sameRequestOrThrow(concurrent, requestHash);
            }
            throw exception;
        }
        return get(creatorId, task.getId());
    }

    @Transactional(readOnly = true)
    public TaskResponse get(Long creatorId, Long taskId) {
        return toResponse(requireOwned(creatorId, taskId));
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(
            Long creatorId,
            TaskStatus status,
            TaskAlgorithm algorithm,
            int page,
            int size
    ) {
        long total = taskMapper.countOwned(creatorId, status, algorithm);
        int offset = Math.multiplyExact(page, size);
        List<TaskResponse> content = taskMapper.findOwnedPage(creatorId, status, algorithm, offset, size)
                .stream()
                .map(this::toResponse)
                .toList();
        return PageResponse.of(content, page, size, total);
    }

    @Transactional
    public TaskResponse cancel(Long creatorId, Long taskId, TaskActionRequest request) {
        ExperimentTask task = requireOwned(creatorId, taskId);
        requireCurrentVersion(task, request.version());
        if (!task.getStatus().canTransitionTo(TaskStatus.CANCELLED)) {
            throw statusConflict("当前任务状态不允许取消");
        }
        if (taskMapper.cancelOwnedWithVersion(taskId, creatorId, request.version()) == 0) {
            throw versionConflict();
        }
        return get(creatorId, taskId);
    }

    @Transactional
    public TaskResponse retry(Long creatorId, Long taskId, TaskActionRequest request) {
        ExperimentTask task = requireOwned(creatorId, taskId);
        requireCurrentVersion(task, request.version());
        if (!task.getStatus().canTransitionTo(TaskStatus.QUEUED)) {
            throw statusConflict("只有失败任务可以重试");
        }
        if (task.getRetryCount() >= task.getMaxRetryCount()) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "TASK_RETRY_LIMIT_EXCEEDED",
                    "任务已达到最大重试次数"
            );
        }
        if (taskMapper.retryOwnedWithVersion(taskId, creatorId, request.version()) == 0) {
            throw versionConflict();
        }
        return get(creatorId, taskId);
    }

    private TaskResponse sameRequestOrThrow(ExperimentTask existing, String requestHash) {
        if (!Objects.equals(existing.getRequestHash(), requestHash)) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "IDEMPOTENCY_KEY_REUSED",
                    "同一幂等键不能用于不同的任务参数"
            );
        }
        return toResponse(existing);
    }

    private ExperimentTask requireOwned(Long creatorId, Long taskId) {
        ExperimentTask task = taskMapper.findOwnedById(taskId, creatorId);
        if (task == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        }
        return task;
    }

    private void requireCurrentVersion(ExperimentTask task, Integer requestedVersion) {
        if (!Objects.equals(task.getLockVersion(), requestedVersion)) {
            throw versionConflict();
        }
    }

    private BusinessException versionConflict() {
        return new BusinessException(
                HttpStatus.CONFLICT,
                "TASK_VERSION_CONFLICT",
                "任务已被其他请求修改，请刷新后重试"
        );
    }

    private BusinessException statusConflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "TASK_STATUS_CONFLICT", message);
    }

    private String normalizeIdempotencyKey(String rawIdempotencyKey) {
        if (rawIdempotencyKey == null || rawIdempotencyKey.isBlank()) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "IDEMPOTENCY_KEY_REQUIRED",
                    "请求头Idempotency-Key不能为空"
            );
        }
        String key = rawIdempotencyKey.trim();
        if (key.length() > MAX_IDEMPOTENCY_KEY_LENGTH) {
            throw new BusinessException(
                    HttpStatus.BAD_REQUEST,
                    "INVALID_IDEMPOTENCY_KEY",
                    "Idempotency-Key不能超过64个字符"
            );
        }
        return key;
    }

    private String requestHash(CreateTaskRequest request, int priority) {
        TrainingConfigRequest config = request.trainingConfig();
        String canonical = String.join(
                "|",
                request.scenarioId().toString(),
                request.algorithm().name(),
                config.maxTrainingSteps().toString(),
                normalizeDecimal(config.learningRate()),
                config.batchSize().toString(),
                normalizeDecimal(config.discountFactor()),
                config.randomSeed().toString(),
                Integer.toString(priority)
        );
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("当前JDK不支持SHA-256", exception);
        }
    }

    private String normalizeDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    private TaskResponse toResponse(ExperimentTask task) {
        return new TaskResponse(
                task.getId(),
                task.getTaskNo(),
                task.getScenarioId(),
                readJson(task.getScenarioSnapshotJson(), ScenarioSnapshot.class, "任务场景快照无法解析"),
                task.getAlgorithm(),
                readJson(task.getTrainingConfigJson(), TrainingConfigRequest.class, "任务训练参数无法解析"),
                task.getPriority(),
                task.getStatus(),
                task.getProgress(),
                task.getRetryCount(),
                task.getMaxRetryCount(),
                task.getErrorMessage(),
                task.getLockVersion(),
                task.getSubmittedAt(),
                task.getStartedAt(),
                task.getFinishedAt(),
                task.getCreatedAt(),
                task.getUpdatedAt()
        );
    }

    private String writeJson(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    private <T> T readJson(String json, Class<T> type, String message) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException(message, exception);
        }
    }
}
