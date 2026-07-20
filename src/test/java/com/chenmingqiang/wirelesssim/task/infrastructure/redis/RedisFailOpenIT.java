package com.chenmingqiang.wirelesssim.task.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/** 连接到明确未监听的端口，验证缓存和限流在Redis故障时都采用Fail Open。 */
@SpringBootTest(properties = {
        "simulation.redis.enabled=true",
        "spring.data.redis.port=16380",
        "spring.data.redis.connect-timeout=100ms",
        "spring.data.redis.timeout=100ms",
        "simulation.execution.enabled=false"
})
class RedisFailOpenIT {

    /** 被测缓存边界。 */
    @Autowired
    private TaskDetailCache taskDetailCache;
    /** 被测限流边界。 */
    @Autowired
    private TaskSubmissionRateLimiter rateLimiter;

    /** Redis连接失败不能阻断MySQL回源或新任务提交。 */
    @Test
    void redisFailureFallsBackWithoutBusinessException() {
        assertThat(taskDetailCache.get(9001L, 8001L)).isEmpty();
        assertThatCode(() -> rateLimiter.acquireOrThrow(9001L)).doesNotThrowAnyException();
    }
}
