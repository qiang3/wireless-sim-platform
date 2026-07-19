package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Outbox发布器的类型安全配置。
 *
 * <p>Spring把{@code application.yml}中的{@code simulation.outbox}配置绑定到该记录，
 * 业务代码不需要自行读取或解析字符串配置。</p>
 */
@ConfigurationProperties("simulation.outbox")
public record OutboxPublisherProperties(
        /** 是否启用Outbox发布流程；实际调度器还会要求dispatch-mode=rabbitmq。 */
        boolean enabled,
        /** 发布器扫描待发送事件的时间间隔。 */
        Duration scanInterval,
        /** 每次最多领取的事件数量。 */
        int batchSize,
        /** SENDING状态允许占用的最长时间，超时后可由其他发布器恢复。 */
        Duration leaseDuration,
        /** 等待RabbitMQ Publisher Confirm的最长时间。 */
        Duration confirmTimeout,
        /** 第一次发布失败后的基础重试延迟。 */
        Duration retryBaseDelay,
        /** 指数退避允许达到的最大重试延迟。 */
        Duration retryMaxDelay
) {

    /** 在应用启动时集中校验配置，避免运行到一半才暴露非法参数。 */
    public OutboxPublisherProperties {
        requirePositive(scanInterval, "Outbox扫描间隔");
        requirePositive(leaseDuration, "Outbox租约时长");
        requirePositive(confirmTimeout, "RabbitMQ确认超时");
        requirePositive(retryBaseDelay, "Outbox基础重试延迟");
        requirePositive(retryMaxDelay, "Outbox最大重试延迟");

        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("Outbox单批数量必须在1到1000之间");
        }
        if (retryMaxDelay.compareTo(retryBaseDelay) < 0) {
            throw new IllegalArgumentException("Outbox最大重试延迟不能小于基础重试延迟");
        }
        if (leaseDuration.compareTo(confirmTimeout.multipliedBy(batchSize)) < 0) {
            throw new IllegalArgumentException("Outbox租约时长不能短于单批消息逐条确认的最坏等待时间");
        }
    }

    /**
     * 返回SQL租约判断使用的秒数。
     *
     * <p>租约小于1秒没有实际意义，因此配置校验后这里至少返回1。</p>
     */
    public long leaseSeconds() {
        return Math.max(1L, leaseDuration.toSeconds());
    }

    /** 校验Duration存在且大于0。 */
    private static void requirePositive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + "必须大于0");
        }
    }
}
