package com.chenmingqiang.wirelesssim.task.infrastructure.redis;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Redis功能的类型安全配置。
 *
 * @param enabled 是否启用任务缓存和提交限流
 * @param taskDetailTtl 任务详情缓存有效期
 * @param rateLimit 用户提交限流参数
 */
@ConfigurationProperties("simulation.redis")
public record SimulationRedisProperties(
        boolean enabled,
        Duration taskDetailTtl,
        RateLimit rateLimit
) {

    /** 启动时校验配置，避免负数TTL或无效阈值运行到业务请求时才暴露。 */
    public SimulationRedisProperties {
        if (taskDetailTtl == null || taskDetailTtl.isZero() || taskDetailTtl.isNegative()) {
            throw new IllegalArgumentException("任务详情缓存TTL必须大于0");
        }
        if (rateLimit == null) {
            throw new IllegalArgumentException("Redis提交限流配置不能为空");
        }
    }

    /**
     * 用户级任务提交固定窗口配置。
     *
     * @param window 计数窗口长度
     * @param maxSubmissions 一个窗口内允许创建的新任务数量
     */
    public record RateLimit(Duration window, int maxSubmissions) {

        /** 校验窗口和最大提交数量都为正数。 */
        public RateLimit {
            if (window == null || window.isZero() || window.isNegative()) {
                throw new IllegalArgumentException("提交限流窗口必须大于0");
            }
            if (maxSubmissions <= 0) {
                throw new IllegalArgumentException("提交限流阈值必须大于0");
            }
        }
    }
}
