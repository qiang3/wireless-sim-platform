package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

/** 验证首次默认次数和非法次数Header。 */
class TaskMessageDeliveryAttemptResolverTest {

    private final TaskMessageDeliveryAttemptResolver resolver = new TaskMessageDeliveryAttemptResolver();

    @Test
    void missingHeaderMeansFirstDelivery() {
        assertThat(resolver.resolve(messageWithAttempt(null))).isEqualTo(1);
    }

    @Test
    void numericHeaderIsReturned() {
        assertThat(resolver.resolve(messageWithAttempt(3))).isEqualTo(3);
    }

    @Test
    void invalidHeaderIsPermanentContractError() {
        assertThatThrownBy(() -> resolver.resolve(messageWithAttempt("two")))
                .isInstanceOf(InvalidTaskMessageException.class);
    }

    private Message messageWithAttempt(Object attempt) {
        MessageProperties properties = new MessageProperties();
        if (attempt != null) {
            properties.setHeader("x-delivery-attempt", attempt);
        }
        return new Message(new byte[0], properties);
    }
}
