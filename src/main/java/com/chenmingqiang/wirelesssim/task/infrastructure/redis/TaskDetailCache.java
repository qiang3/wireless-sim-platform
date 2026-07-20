package com.chenmingqiang.wirelesssim.task.infrastructure.redis;

import com.chenmingqiang.wirelesssim.task.api.TaskResponse;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 任务详情Redis缓存适配器。
 *
 * <p>业务层只依赖本类提供的get/put/evict语义，不直接操作Redis命令。
 * 所有Redis异常都在本边界降级，MySQL始终是任务事实来源。</p>
 */
@Component
public class TaskDetailCache {

    /** 统一键前缀，便于在Redis中识别本项目数据。 */
    public static final String KEY_PREFIX = "wireless-sim:task:detail:";

    /** 记录缓存损坏或Redis不可用时的降级原因。 */
    private static final Logger log = LoggerFactory.getLogger(TaskDetailCache.class);
    /** Redis连接失败后暂停访问5秒，避免任务每次进度更新都重复等待连接超时。 */
    private static final long FAILURE_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(5);

    /** 执行Redis字符串读写命令。 */
    private final StringRedisTemplate redisTemplate;
    /** 把TaskResponse转换为JSON字符串。 */
    private final ObjectMapper objectMapper;
    /** 提供启用开关和5秒TTL。 */
    private final SimulationRedisProperties properties;
    /** 使用单调时钟记录下一次允许尝试Redis的时间。 */
    private volatile long retryAfterNanos;

    /** 通过构造方法注入Redis、JSON和类型安全配置。 */
    public TaskDetailCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            SimulationRedisProperties properties
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    /**
     * 读取当前用户的任务详情缓存。
     * Redis关闭、未命中、内容损坏或连接异常都返回empty，让调用方回源MySQL。
     */
    public Optional<TaskResponse> get(long userId, long taskId) {
        if (!canAccessRedis()) {
            return Optional.empty();
        }
        String key = key(userId, taskId);
        try {
            String json = redisTemplate.opsForValue().get(key);
            if (json == null) {
                return Optional.empty();
            }
            TaskResponse response = objectMapper.readValue(json, TaskResponse.class);
            if (response.id() == null || response.id() != taskId) {
                redisTemplate.delete(key);
                return Optional.empty();
            }
            return Optional.of(response);
        } catch (JacksonException exception) {
            log.warn("任务详情缓存内容损坏，已删除并回源MySQL：userId={}, taskId={}, error={}",
                    userId, taskId, exception.getMessage());
            safeDelete(key);
            return Optional.empty();
        } catch (RuntimeException exception) {
            markTemporarilyUnavailable();
            log.warn("任务详情缓存读取失败，已降级到MySQL：userId={}, taskId={}, error={}",
                    userId, taskId, exception.getMessage());
            return Optional.empty();
        }
    }

    /** MySQL查询成功后，以短TTL写入可重建的任务响应JSON。 */
    public void put(long userId, long taskId, TaskResponse response) {
        if (!canAccessRedis()) {
            return;
        }
        try {
            redisTemplate.opsForValue().set(
                    key(userId, taskId),
                    objectMapper.writeValueAsString(response),
                    properties.taskDetailTtl()
            );
        } catch (JacksonException exception) {
            log.warn("任务详情无法序列化，跳过缓存写入：userId={}, taskId={}, error={}",
                    userId, taskId, exception.getMessage());
        } catch (RuntimeException exception) {
            markTemporarilyUnavailable();
            log.warn("任务详情缓存写入失败，不影响MySQL结果返回：userId={}, taskId={}, error={}",
                    userId, taskId, exception.getMessage());
        }
    }

    /** 任务数据发生变化后删除缓存，让下一次读取回源MySQL。 */
    public void evict(long userId, long taskId) {
        if (!canAccessRedis()) {
            return;
        }
        safeDelete(key(userId, taskId));
    }

    /** 删除失败只记录警告；缓存最多保留到短TTL结束，不能影响数据库事务。 */
    private void safeDelete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException exception) {
            markTemporarilyUnavailable();
            log.warn("任务详情缓存删除失败，将等待TTL自然失效：key={}, error={}", key, exception.getMessage());
        }
    }

    /** Redis功能开启且不处于短暂故障退避期时才发起网络访问。 */
    private boolean canAccessRedis() {
        return properties.enabled() && System.nanoTime() >= retryAfterNanos;
    }

    /** 使用单调时钟设置5秒本地退避，不受系统时间调整影响。 */
    private void markTemporarilyUnavailable() {
        retryAfterNanos = System.nanoTime() + FAILURE_BACKOFF_NANOS;
    }

    /** 用户ID和任务ID共同组成缓存键，避免不同用户共享授权结果。 */
    public String key(long userId, long taskId) {
        return KEY_PREFIX + userId + ":" + taskId;
    }
}
