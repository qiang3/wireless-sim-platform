package com.chenmingqiang.wirelesssim.task.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 使用真实Redis并发执行Lua脚本，验证阈值不会因并发竞争被突破。 */
@SpringBootTest(properties = {
        "simulation.redis.enabled=true",
        "simulation.redis.rate-limit.window=60s",
        "simulation.redis.rate-limit.max-submissions=5",
        "simulation.execution.enabled=false"
})
class TaskSubmissionRateLimiterIT {

    /** 被测Lua限流器。 */
    @Autowired
    private TaskSubmissionRateLimiter rateLimiter;
    /** 测试后删除计数键。 */
    @Autowired
    private StringRedisTemplate redisTemplate;

    /** 每次测试使用不会与数据库用户冲突的正数ID。 */
    private final long userId = Math.abs(UUID.randomUUID().getLeastSignificantBits());

    /** 清除限流计数。 */
    @AfterEach
    void cleanUp() {
        redisTemplate.delete(rateLimiter.key(userId));
    }

    /** 二十个并发请求中只能有五个得到额度。 */
    @Test
    void allowsExactlyConfiguredNumberUnderConcurrency() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            List<Callable<Boolean>> calls = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                calls.add(() -> {
                    try {
                        rateLimiter.acquireOrThrow(userId);
                        return true;
                    } catch (BusinessException exception) {
                        assertThat(exception.getCode()).isEqualTo("TASK_SUBMISSION_RATE_LIMITED");
                        return false;
                    }
                });
            }
            List<Future<Boolean>> results = executor.invokeAll(calls);
            long allowed = results.stream().filter(result -> get(result)).count();
            assertThat(allowed).isEqualTo(5);
            assertThat(redisTemplate.getExpire(rateLimiter.key(userId))).isPositive();
        } finally {
            executor.shutdownNow();
        }
    }

    /** 把Future受检异常转换为测试失败。 */
    private boolean get(Future<Boolean> future) {
        try {
            return future.get();
        } catch (Exception exception) {
            throw new AssertionError("并发限流任务执行失败", exception);
        }
    }
}
