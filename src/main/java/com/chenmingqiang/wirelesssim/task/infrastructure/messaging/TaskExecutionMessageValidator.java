package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import com.chenmingqiang.wirelesssim.task.application.TaskOutboxEventFactory;
import java.util.Objects;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

/** 把原始AMQP消息解析为版本化业务消息，并核对载荷与消息属性。 */
@Component
public class TaskExecutionMessageValidator {

    /** 当前消费者支持的消息结构版本。 */
    public static final int SUPPORTED_SCHEMA_VERSION = 1;

    /** Spring Boot统一配置的Jackson JSON映射器。 */
    private final ObjectMapper objectMapper;

    /** 通过构造器注入JSON映射器。 */
    public TaskExecutionMessageValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 解析并校验一条RabbitMQ消息，失败时抛出永久契约异常。 */
    public TaskExecutionRequestedMessage parseAndValidate(Message rawMessage) {
        if (rawMessage == null) {
            throw new InvalidTaskMessageException("RabbitMQ消息不能为空");
        }

        TaskExecutionRequestedMessage message;
        try {
            message = objectMapper.readValue(rawMessage.getBody(), TaskExecutionRequestedMessage.class);
        } catch (RuntimeException exception) {
            throw new InvalidTaskMessageException("消息JSON无法解析", exception);
        }

        require(message.eventId() != null && !message.eventId().isBlank(), "eventId不能为空");
        require(message.taskId() != null && message.taskId() > 0, "taskId必须为正数");
        require(message.attemptNo() != null && message.attemptNo() > 0, "attemptNo必须为正数");
        require(TaskOutboxEventFactory.EVENT_TYPE.equals(message.eventType()), "eventType不受支持");
        require(Objects.equals(SUPPORTED_SCHEMA_VERSION, message.schemaVersion()), "schemaVersion不受支持");
        require(message.occurredAt() != null, "occurredAt不能为空");

        MessageProperties properties = rawMessage.getMessageProperties();
        require(message.eventId().equals(properties.getMessageId()), "AMQP messageId与载荷eventId不一致");
        require(message.eventType().equals(properties.getType()), "AMQP type与载荷eventType不一致");
        require(headerAsLong(properties, "attemptNo") == message.attemptNo().longValue(),
                "AMQP attemptNo与载荷不一致");
        require(headerAsLong(properties, "schemaVersion") == message.schemaVersion().longValue(),
                "AMQP schemaVersion与载荷不一致");
        return message;
    }

    /** 把Rabbit header中的数字安全转换为long。 */
    private long headerAsLong(MessageProperties properties, String name) {
        Object value = properties.getHeaders().get(name);
        if (!(value instanceof Number number)) {
            throw new InvalidTaskMessageException("AMQP " + name + " Header缺失或不是数字");
        }
        return number.longValue();
    }

    /** 契约条件失败时统一抛出永久消息异常。 */
    private void require(boolean condition, String message) {
        if (!condition) {
            throw new InvalidTaskMessageException(message);
        }
    }
}
