package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;

/** 验证5秒起步、指数翻倍、5分钟封顶的退避规则。 */
class OutboxRetryBackoffCalculatorTest {

    /** 覆盖前几次翻倍和大次数封顶，避免溢出。 */
    @Test
    void calculatesCappedExponentialDelay() {
        OutboxRetryBackoffCalculator calculator = new OutboxRetryBackoffCalculator(properties());

        assertThat(calculator.calculate(1)).isEqualTo(Duration.ofSeconds(5));
        assertThat(calculator.calculate(2)).isEqualTo(Duration.ofSeconds(10));
        assertThat(calculator.calculate(3)).isEqualTo(Duration.ofSeconds(20));
        assertThat(calculator.calculate(6)).isEqualTo(Duration.ofSeconds(160));
        assertThat(calculator.calculate(7)).isEqualTo(Duration.ofMinutes(5));
        assertThat(calculator.calculate(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    /** 创建与正式默认值一致的测试配置。 */
    private OutboxPublisherProperties properties() {
        return new OutboxPublisherProperties(
                true,
                Duration.ofSeconds(1),
                20,
                Duration.ofMinutes(2),
                Duration.ofSeconds(5),
                Duration.ofSeconds(5),
                Duration.ofMinutes(5)
        );
    }
}
