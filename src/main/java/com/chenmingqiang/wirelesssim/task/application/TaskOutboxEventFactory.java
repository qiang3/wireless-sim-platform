package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.TaskExecutionRequestedMessage;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 为任务首次提交和人工重试创建格式一致的Outbox事件。
 *
 * <p>把事件常量、UUID、UTC时间、JSON序列化和优先级映射集中在一个组件中，
 * 避免不同业务入口生成不兼容消息。</p>
 */
@Component
public class TaskOutboxEventFactory {

    /** 当前Outbox聚合类型。 */
    public static final String AGGREGATE_TYPE = "EXPERIMENT_TASK";
    /** 当前唯一的任务消息事件类型。 */
    public static final String EVENT_TYPE = "TASK_EXECUTION_REQUESTED";
    /** 当前消息JSON结构版本。 */
    public static final int SCHEMA_VERSION = 1;
    /** RabbitMQ主执行队列允许的最高优先级。 */
    private static final int MAX_MESSAGE_PRIORITY = 5;

    /** 把轻量消息对象序列化为JSON。 */
    private final ObjectMapper objectMapper;
    /** 提供UTC当前时间；抽成依赖后可在测试中使用固定时钟。 */
    private final Clock clock;

    /** 生产环境构造方法，默认使用UTC系统时钟。 */
    @Autowired
    public TaskOutboxEventFactory(ObjectMapper objectMapper) {
        this(objectMapper, Clock.systemUTC());
    }

    /** 测试专用构造方法，允许注入固定时钟。 */
    TaskOutboxEventFactory(ObjectMapper objectMapper, Clock clock) {
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * 创建一条待发布的任务执行请求事件。
     *
     * @param taskId 已经插入数据库的任务ID
     * @param attemptNo 本次业务执行尝试号
     * @param businessPriority API接收的1到10级业务优先级
     * @return 可以交给OutboxEventMapper插入的事件对象
     */
    public OutboxEvent createExecutionRequested(Long taskId, int attemptNo, int businessPriority) {
        if (taskId == null || taskId <= 0) {
            throw new IllegalArgumentException("taskId必须大于0");
        }
        if (attemptNo <= 0) {
            throw new IllegalArgumentException("attemptNo必须大于0");
        }

        String eventId = UUID.randomUUID().toString();
        Instant occurredAt = clock.instant();
        TaskExecutionRequestedMessage message = new TaskExecutionRequestedMessage(
                eventId,
                taskId,
                attemptNo,
                EVENT_TYPE,
                SCHEMA_VERSION,
                occurredAt
        );

        OutboxEvent event = new OutboxEvent();
        event.setEventId(eventId);
        event.setAggregateType(AGGREGATE_TYPE);
        event.setAggregateId(taskId);
        event.setEventType(EVENT_TYPE);
        event.setAttemptNo(attemptNo);
        event.setSchemaVersion(SCHEMA_VERSION);
        event.setPayloadJson(writePayload(message));
        event.setPriority(toMessagePriority(businessPriority));
        // DATETIME不携带时区，本项目约定occurred_at按UTC保存。
        event.setOccurredAt(LocalDateTime.ofInstant(occurredAt, ZoneOffset.UTC));
        return event;
    }

    /** 把业务优先级1—10向上分组映射到RabbitMQ优先级1—5。 */
    int toMessagePriority(int businessPriority) {
        if (businessPriority < 1 || businessPriority > 10) {
            throw new IllegalArgumentException("businessPriority必须在1到10之间");
        }
        return Math.min(MAX_MESSAGE_PRIORITY, (businessPriority + 1) / 2);
    }

    /** 序列化轻量消息；失败时抛出运行时异常，使外层任务事务整体回滚。 */
    private String writePayload(TaskExecutionRequestedMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (JacksonException exception) {
            throw new IllegalStateException("任务执行事件序列化失败", exception);
        }
    }
}
