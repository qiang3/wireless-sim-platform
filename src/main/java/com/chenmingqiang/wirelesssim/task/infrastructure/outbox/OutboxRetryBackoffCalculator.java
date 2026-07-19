package com.chenmingqiang.wirelesssim.task.infrastructure.outbox;

import java.time.Duration;
import org.springframework.stereotype.Component;

/**
 * 计算Outbox发布失败后的封顶指数退避时间。
 *
 * <p>第1次失败使用基础延迟，之后每次翻倍，达到最大延迟后保持不变。</p>
 */
@Component
public class OutboxRetryBackoffCalculator {

    /** 类型安全的基础延迟和最大延迟配置。 */
    private final OutboxPublisherProperties properties;

    /** 由Spring注入Outbox发布配置。 */
    public OutboxRetryBackoffCalculator(OutboxPublisherProperties properties) {
        this.properties = properties;
    }

    /**
     * 根据已经发生的发布尝试次数计算下一次延迟。
     *
     * @param publishAttempts 当前数据库中的发布尝试次数，领取时已经递增
     * @return 基础延迟乘2的指数结果，并以最大延迟封顶
     */
    public Duration calculate(int publishAttempts) {
        if (publishAttempts < 1) {
            throw new IllegalArgumentException("发布尝试次数必须大于等于1");
        }

        Duration delay = properties.retryBaseDelay();
        Duration maximum = properties.retryMaxDelay();
        for (int attempt = 1; attempt < publishAttempts; attempt++) {
            if (delay.compareTo(maximum) >= 0) {
                return maximum;
            }
            // 先比较一半上限，避免Duration乘法溢出后才封顶。
            if (delay.compareTo(maximum.dividedBy(2)) > 0) {
                return maximum;
            }
            delay = delay.multipliedBy(2);
        }
        return delay.compareTo(maximum) > 0 ? maximum : delay;
    }
}
