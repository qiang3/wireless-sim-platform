package com.chenmingqiang.wirelesssim.task.infrastructure.messaging;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 仿真消息配置。
 *
 * @param retryDelay 消息临时失败后在重试队列中等待的时间
 * @param maxDeliveryAttempts 同一消息允许的最大投递处理次数，阶段8.6实现消费者重试时使用
 * @param maxPriority RabbitMQ主执行队列支持的最高消息优先级
 * @param forwardConfirmTimeout 消费者转发重试或死信消息时等待Publisher Confirm的最长时间
 */
@ConfigurationProperties(prefix = "simulation.messaging")
public record SimulationMessagingProperties(
        Duration retryDelay,
        int maxDeliveryAttempts,
        int maxPriority,
        Duration forwardConfirmTimeout
) {

    /** 在应用启动阶段校验配置，避免错误参数等到消息运行时才暴露。 */
    public SimulationMessagingProperties {
        if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()) {
            throw new IllegalArgumentException("simulation.messaging.retry-delay必须大于0");
        }
        if (retryDelay.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("simulation.messaging.retry-delay不能超过Integer毫秒范围");
        }
        if (maxDeliveryAttempts < 1) {
            throw new IllegalArgumentException("simulation.messaging.max-delivery-attempts必须大于等于1");
        }
        if (maxPriority < 1 || maxPriority > 255) {
            throw new IllegalArgumentException("simulation.messaging.max-priority必须在1到255之间");
        }
        if (forwardConfirmTimeout == null
                || forwardConfirmTimeout.isZero()
                || forwardConfirmTimeout.isNegative()) {
            throw new IllegalArgumentException("simulation.messaging.forward-confirm-timeout必须大于0");
        }
    }

    /** RabbitMQ队列参数使用整数毫秒，集中转换可以避免各处重复强制类型转换。 */
    public int retryDelayMillis() {
        return Math.toIntExact(retryDelay.toMillis());
    }
}
