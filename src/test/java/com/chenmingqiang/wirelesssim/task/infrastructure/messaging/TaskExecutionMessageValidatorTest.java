package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import tools.jackson.databind.ObjectMapper;

/** 验证载荷字段、消息版本和AMQP属性必须表达同一事实。 */
class TaskExecutionMessageValidatorTest {

    private final TaskExecutionMessageValidator validator =
            new TaskExecutionMessageValidator(new ObjectMapper());

    @Test
    void acceptsSupportedMessageWithMatchingProperties() {
        TaskExecutionRequestedMessage parsed = validator.parseAndValidate(validRawMessage(1));

        assertThat(parsed.taskId()).isEqualTo(123L);
        assertThat(parsed.attemptNo()).isEqualTo(1);
    }

    @Test
    void rejectsMalformedJson() {
        MessageProperties properties = validProperties(1);
        Message raw = new Message("{bad-json".getBytes(StandardCharsets.UTF_8), properties);

        assertThatThrownBy(() -> validator.parseAndValidate(raw))
                .isInstanceOf(InvalidTaskMessageException.class)
                .hasMessageContaining("JSON");
    }

    @Test
    void rejectsUnsupportedSchemaVersion() {
        assertThatThrownBy(() -> validator.parseAndValidate(validRawMessage(2)))
                .isInstanceOf(InvalidTaskMessageException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void rejectsMismatchedAmqpAttemptHeader() {
        Message raw = validRawMessage(1);
        raw.getMessageProperties().setHeader("attemptNo", 2);

        assertThatThrownBy(() -> validator.parseAndValidate(raw))
                .isInstanceOf(InvalidTaskMessageException.class)
                .hasMessageContaining("attemptNo");
    }

    private Message validRawMessage(int schemaVersion) {
        String json = """
                {
                  "eventId":"event-123",
                  "taskId":123,
                  "attemptNo":1,
                  "eventType":"TASK_EXECUTION_REQUESTED",
                  "schemaVersion":%d,
                  "occurredAt":"2026-07-19T10:00:00Z"
                }
                """.formatted(schemaVersion);
        return new Message(json.getBytes(StandardCharsets.UTF_8), validProperties(schemaVersion));
    }

    private MessageProperties validProperties(int schemaVersion) {
        MessageProperties properties = new MessageProperties();
        properties.setMessageId("event-123");
        properties.setType("TASK_EXECUTION_REQUESTED");
        properties.setHeader("attemptNo", 1);
        properties.setHeader("schemaVersion", schemaVersion);
        return properties;
    }
}
