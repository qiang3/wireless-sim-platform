package com.chenmingqiang.wirelesssim.task.infrastructure.redis;

import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import java.util.Collections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

/** 使用Redis Lua脚本限制单个用户在固定时间窗口内创建的新任务数量。 */
@Component
public class TaskSubmissionRateLimiter {

    /** 用户提交计数键前缀。 */
    public static final String KEY_PREFIX = "wireless-sim:rate:task-submit:";

    /** Redis不可用时记录Fail Open告警。 */
    private static final Logger log = LoggerFactory.getLogger(TaskSubmissionRateLimiter.class);

    /**
     * 原子执行INCR与首次PEXPIRE。
     * 如果拆成两个客户端命令，进程可能在INCR后宕机并留下永不过期的限流键。
     */
    private static final DefaultRedisScript<Long> ACQUIRE_SCRIPT = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('PEXPIRE', KEYS[1], ARGV[1])
            end
            return current
            """, Long.class);

    /** 执行Lua脚本的Redis模板。 */
    private final StringRedisTemplate redisTemplate;
    /** 提供开关、60秒窗口和最多5次阈值。 */
    private final SimulationRedisProperties properties;

    /** 构造限流器。 */
    public TaskSubmissionRateLimiter(
            StringRedisTemplate redisTemplate,
            SimulationRedisProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
    }

    /**
     * 为一个准备创建的新任务申请额度。
     * 超限抛出稳定的429业务异常；Redis不可用时Fail Open，保证核心提交能力可用。
     */
    public void acquireOrThrow(long userId) {
        if (!properties.enabled()) {
            return;
        }
        try {
            Long current = redisTemplate.execute(
                    ACQUIRE_SCRIPT,
                    Collections.singletonList(key(userId)),
                    Long.toString(properties.rateLimit().window().toMillis())
            );
            if (current != null && current > properties.rateLimit().maxSubmissions()) {
                throw new BusinessException(
                        HttpStatus.TOO_MANY_REQUESTS,
                        "TASK_SUBMISSION_RATE_LIMITED",
                        "任务提交过于频繁，请稍后再试"
                );
            }
        } catch (BusinessException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            log.warn("Redis提交限流不可用，已按Fail Open放行：userId={}, error={}",
                    userId, exception.getMessage());
        }
    }

    /** 每个用户使用独立计数键。 */
    public String key(long userId) {
        return KEY_PREFIX + userId;
    }
}
