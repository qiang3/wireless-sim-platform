package com.chenmingqiang.wirelesssim.task.infrastructure.redis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import com.chenmingqiang.wirelesssim.task.api.CreateTaskRequest;
import com.chenmingqiang.wirelesssim.task.api.TaskActionRequest;
import com.chenmingqiang.wirelesssim.task.api.TaskResponse;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import com.chenmingqiang.wirelesssim.task.application.TaskService;
import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 使用真实MySQL和Redis验证Cache Aside、提交后失效及限流与幂等的组合语义。 */
@SpringBootTest(properties = {
        "simulation.redis.enabled=true",
        "simulation.redis.task-detail-ttl=5s",
        "simulation.redis.rate-limit.window=60s",
        "simulation.redis.rate-limit.max-submissions=5",
        "simulation.execution.enabled=false"
})
class TaskRedisFlowIT {

    /** 被测任务应用服务。 */
    @Autowired
    private TaskService taskService;
    /** 直接检查缓存内容和TTL。 */
    @Autowired
    private TaskDetailCache taskDetailCache;
    /** 清理本测试创建的Redis键。 */
    @Autowired
    private StringRedisTemplate redisTemplate;
    /** 创建和清理真实MySQL测试数据。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 生成合法的场景JSON。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 每个测试可能创建一个独立用户，统一按外键顺序清理。 */
    private final List<Long> createdUserIds = new ArrayList<>();

    /** 删除Redis短期键和MySQL测试记录，保证测试可重复执行。 */
    @AfterEach
    void cleanUp() {
        for (Long userId : createdUserIds) {
            redisTemplate.delete(TaskSubmissionRateLimiter.KEY_PREFIX + userId);
            List<Long> taskIds = jdbcTemplate.queryForList(
                    "SELECT id FROM experiment_task WHERE creator_id=?",
                    Long.class,
                    userId
            );
            for (Long taskId : taskIds) {
                redisTemplate.delete(taskDetailCache.key(userId, taskId));
            }
            jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_type='EXPERIMENT_TASK' "
                    + "AND aggregate_id IN (SELECT id FROM experiment_task WHERE creator_id=?)", userId);
            jdbcTemplate.update("DELETE FROM simulation_result WHERE task_id IN "
                    + "(SELECT id FROM experiment_task WHERE creator_id=?)", userId);
            jdbcTemplate.update("DELETE FROM task_execution WHERE task_id IN "
                    + "(SELECT id FROM experiment_task WHERE creator_id=?)", userId);
            jdbcTemplate.update("DELETE FROM experiment_task WHERE creator_id=?", userId);
            jdbcTemplate.update("DELETE FROM simulation_scenario WHERE owner_id=?", userId);
            jdbcTemplate.update("DELETE FROM app_user WHERE id=?", userId);
        }
    }

    /** 首次读取写缓存，用户隔离生效，取消事务提交后缓存被删除。 */
    @Test
    void cachesOwnedTaskAndEvictsOnlyAfterSuccessfulMutation() throws Exception {
        Fixture fixture = createFixture();
        TaskResponse submitted = taskService.submit(
                fixture.userId(),
                "redis-cache-task",
                taskRequest(fixture.scenarioId())
        );

        TaskResponse firstRead = taskService.get(fixture.userId(), submitted.id());
        String cacheKey = taskDetailCache.key(fixture.userId(), submitted.id());
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNotNull();
        assertThat(redisTemplate.getExpire(cacheKey)).isBetween(0L, 5L);
        assertThat(taskDetailCache.get(fixture.userId(), submitted.id()))
                .hasValueSatisfying(cached -> assertThat(cached.status()).isEqualTo(TaskStatus.PENDING));
        assertThat(taskDetailCache.get(fixture.userId() + 1, submitted.id())).isEmpty();

        TaskResponse cancelled = taskService.cancel(
                fixture.userId(),
                submitted.id(),
                new TaskActionRequest(firstRead.version())
        );

        assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNull();
        assertThat(taskService.get(fixture.userId(), submitted.id()).status()).isEqualTo(TaskStatus.CANCELLED);
    }

    /** 五个新任务耗尽额度后，幂等重放仍返回原任务，第六个新任务返回429。 */
    @Test
    void idempotentReplayDoesNotConsumeNewTaskQuota() throws Exception {
        Fixture fixture = createFixture();
        TaskResponse first = null;
        for (int index = 1; index <= 5; index++) {
            TaskResponse created = taskService.submit(
                    fixture.userId(),
                    "redis-rate-" + index,
                    taskRequest(fixture.scenarioId())
            );
            if (index == 1) {
                first = created;
            }
        }

        TaskResponse replay = taskService.submit(
                fixture.userId(),
                "redis-rate-1",
                taskRequest(fixture.scenarioId())
        );
        assertThat(replay.id()).isEqualTo(first.id());

        assertThatThrownBy(() -> taskService.submit(
                fixture.userId(),
                "redis-rate-6",
                taskRequest(fixture.scenarioId())
        )).isInstanceOfSatisfying(BusinessException.class, exception -> {
            assertThat(exception.getStatus().value()).isEqualTo(429);
            assertThat(exception.getCode()).isEqualTo("TASK_SUBMISSION_RATE_LIMITED");
        });

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experiment_task WHERE creator_id=?",
                Integer.class,
                fixture.userId()
        )).isEqualTo(5);
    }

    /**
     * 人为写入无法反序列化的缓存值，验证缓存适配器会删除脏数据、回源MySQL并重建正确缓存。
     * 该测试模拟序列化格式升级、人工误操作等导致的缓存污染，证明Redis损坏不会污染事实数据。
     */
    @Test
    void corruptedTaskCacheIsDeletedAndRebuiltFromMySql() throws Exception {
        Fixture fixture = createFixture();
        TaskResponse submitted = taskService.submit(
                fixture.userId(),
                "redis-corrupted-cache",
                taskRequest(fixture.scenarioId())
        );
        String cacheKey = taskDetailCache.key(fixture.userId(), submitted.id());
        redisTemplate.opsForValue().set(cacheKey, "{broken-json", Duration.ofSeconds(5));

        TaskResponse recovered = taskService.get(fixture.userId(), submitted.id());

        assertThat(recovered.id()).isEqualTo(submitted.id());
        assertThat(recovered.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(redisTemplate.opsForValue().get(cacheKey))
                .isNotNull()
                .isNotEqualTo("{broken-json");
        assertThat(taskDetailCache.get(fixture.userId(), submitted.id()))
                .hasValueSatisfying(cached -> assertThat(cached.id()).isEqualTo(submitted.id()));
    }

    /** 创建最小合法用户和无线通信场景，供任务服务集成测试使用。 */
    private Fixture createFixture() throws Exception {
        String username = "redis_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        jdbcTemplate.update(
                "INSERT INTO app_user(username,password_hash,role,status) VALUES(?,?,'USER','ACTIVE')",
                username,
                "$2a$10$redisIntegrationTestPasswordHashOnly000000000000000000"
        );
        Long userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username=?",
                Long.class,
                username
        );
        createdUserIds.add(userId);

        ScenarioConfigRequest config = new ScenarioConfigRequest(
                3, 2, 100,
                new BigDecimal("1.0"),
                new BigDecimal("2.0"),
                new BigDecimal("10.0"),
                new BigDecimal("20.0"),
                new BigDecimal("5.0"),
                new BigDecimal("1.0"),
                AccessScheme.RSMA,
                42L
        );
        String scenarioName = "Redis集成场景-" + UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO simulation_scenario(owner_id,name,description,objective,config_json) "
                        + "VALUES(?,?,?,'THROUGHPUT',CAST(? AS JSON))",
                userId,
                scenarioName,
                "Redis阶段测试",
                objectMapper.writeValueAsString(config)
        );
        Long scenarioId = jdbcTemplate.queryForObject(
                "SELECT id FROM simulation_scenario WHERE owner_id=? AND name=?",
                Long.class,
                userId,
                scenarioName
        );
        return new Fixture(userId, scenarioId);
    }

    /** 创建固定训练参数，保证不同幂等重放生成相同请求摘要。 */
    private CreateTaskRequest taskRequest(long scenarioId) {
        return new CreateTaskRequest(
                scenarioId,
                TaskAlgorithm.GRPO,
                new TrainingConfigRequest(
                        100,
                        new BigDecimal("0.001"),
                        32,
                        new BigDecimal("0.99"),
                        42L
                ),
                5
        );
    }

    /** 测试创建的用户与场景主键。 */
    private record Fixture(long userId, long scenarioId) {
    }
}
