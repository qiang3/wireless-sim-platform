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
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
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

// Spring说明：将该类注册为业务服务Bean，其他组件可通过构造方法注入它。

/**
 * 任务应用服务：处理任务提交、查询、取消和重试，并实现幂等、快照和乐观锁规则。
 */
@Service
public class TaskService {

    // 优先级未显式提交时使用中间值，避免默认任务被误判为最高或最低优先级。
    /** 字段说明：`DEFAULT_PRIORITY`保存该对象运行所需的依赖、配置或状态。 */
    private static final int DEFAULT_PRIORITY = 5;
    /** 限制幂等键长度，避免请求头和唯一索引存储无界增长。 */
    private static final int MAX_IDEMPOTENCY_KEY_LENGTH = 64;

    /** 任务表 MyBatis 数据访问代理。 */
    private final TaskMapper taskMapper;
    /** 读取任务引用的场景，并校验场景属于提交人。 */
    private final ScenarioMapper scenarioMapper;
    /** 负责场景快照、训练参数和响应对象的 JSON 转换。 */
    private final ObjectMapper objectMapper;
    /** 把待发布任务事件写入Outbox表的MyBatis代理。 */
    private final OutboxEventMapper outboxEventMapper;
    /** 统一生成版本化任务执行请求事件。 */
    private final TaskOutboxEventFactory outboxEventFactory;

    /** 通过构造方法注入任务、场景、Outbox和JSON依赖。 */
    public TaskService(
            TaskMapper taskMapper,
            ScenarioMapper scenarioMapper,
            ObjectMapper objectMapper,
            OutboxEventMapper outboxEventMapper,
            TaskOutboxEventFactory outboxEventFactory
    ) {
        this.taskMapper = taskMapper;
        this.scenarioMapper = scenarioMapper;
        this.objectMapper = objectMapper;
        this.outboxEventMapper = outboxEventMapper;
        this.outboxEventFactory = outboxEventFactory;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /**
     * 提交任务。同一用户使用相同幂等键和相同参数重试时返回原任务；
     * 相同幂等键搭配不同参数时返回冲突，防止客户端误把两个业务请求合并。
     */
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

        // 提交时复制一份场景快照，之后即使原场景被修改，历史任务仍可复现当时输入。
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
            // 两个并发请求可能同时通过前置查询；数据库唯一键负责最终去重。
            ExperimentTask concurrent = taskMapper.findByCreatorAndIdempotencyKey(creatorId, idempotencyKey);
            if (concurrent != null) {
                return sameRequestOrThrow(concurrent, requestHash);
            }
            throw exception;
        }
        // 任务和事件位于同一个@Transactional事务：任意一次插入失败都会一起回滚。
        insertExecutionRequestedEvent(task, 1);
        return get(creatorId, task.getId());
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional(readOnly = true)
    /** 方法说明：`get`封装下面这段业务或转换逻辑。 */
    public TaskResponse get(Long creatorId, Long taskId) {
        return toResponse(requireOwned(creatorId, taskId));
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

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

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** 按状态机和 lockVersion 协作取消任务；运行中的 Worker 会在步骤边界检测到取消。 */
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

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** 把未超过次数上限的 FAILED 任务重新置为 QUEUED，保留原快照和参数。 */
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
        // 重新读取更新后的retry_count和priority，确保事件尝试号来自数据库最终状态。
        ExperimentTask retried = requireOwned(creatorId, taskId);
        insertExecutionRequestedEvent(retried, retried.getRetryCount() + 1);
        return toResponse(retried);
    }

    /**
     * 生成并插入一条任务执行请求事件。
     *
     * <p>本方法由submit或retry的事务调用，不自行开启新事务，因此事件插入异常会让
     * 前面已经执行的任务INSERT或UPDATE一并回滚。</p>
     */
    private void insertExecutionRequestedEvent(ExperimentTask task, int attemptNo) {
        int affectedRows = outboxEventMapper.insertPending(
                outboxEventFactory.createExecutionRequested(task.getId(), attemptNo, task.getPriority())
        );
        if (affectedRows != 1) {
            throw new IllegalStateException("任务执行事件写入失败");
        }
    }

    /** 比较请求摘要，区分“同一请求重放”和“错误复用幂等键”。 */
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

    /** 方法说明：`requireOwned`封装下面这段业务或转换逻辑。 */
    private ExperimentTask requireOwned(Long creatorId, Long taskId) {
        ExperimentTask task = taskMapper.findOwnedById(taskId, creatorId);
        if (task == null) {
            throw new BusinessException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "任务不存在");
        }
        return task;
    }

    /** 方法说明：`requireCurrentVersion`封装下面这段业务或转换逻辑。 */
    private void requireCurrentVersion(ExperimentTask task, Integer requestedVersion) {
        if (!Objects.equals(task.getLockVersion(), requestedVersion)) {
            throw versionConflict();
        }
    }

    /** 方法说明：`versionConflict`封装下面这段业务或转换逻辑。 */
    private BusinessException versionConflict() {
        return new BusinessException(
                HttpStatus.CONFLICT,
                "TASK_VERSION_CONFLICT",
                "任务已被其他请求修改，请刷新后重试"
        );
    }

    /** 方法说明：`statusConflict`封装下面这段业务或转换逻辑。 */
    private BusinessException statusConflict(String message) {
        return new BusinessException(HttpStatus.CONFLICT, "TASK_STATUS_CONFLICT", message);
    }

    /** 方法说明：`normalizeIdempotencyKey`封装下面这段业务或转换逻辑。 */
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

    /** 将影响执行结果的字段按固定顺序规范化，再生成 SHA-256 请求摘要。 */
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

    /** 让 0.10 与 0.1 得到相同文本，避免等价请求产生不同摘要。 */
    private String normalizeDecimal(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }

    /** 方法说明：`toResponse`封装下面这段业务或转换逻辑。 */
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

    /** 方法说明：`writeJson`封装下面这段业务或转换逻辑。 */
    private String writeJson(Object value, String message) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException exception) {
            throw new IllegalStateException(message, exception);
        }
    }

    /** 方法说明：`readJson`封装下面这段业务或转换逻辑。 */
    private <T> T readJson(String json, Class<T> type, String message) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JacksonException exception) {
            throw new IllegalStateException(message, exception);
        }
    }
}
