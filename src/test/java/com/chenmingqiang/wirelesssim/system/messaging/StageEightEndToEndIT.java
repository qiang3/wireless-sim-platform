package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import com.chenmingqiang.wirelesssim.task.api.CreateTaskRequest;
import com.chenmingqiang.wirelesssim.task.api.TaskResponse;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import com.chenmingqiang.wirelesssim.task.application.TaskService;
import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.domain.OutboxStatus;
import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.domain.TaskStatus;
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import com.chenmingqiang.wirelesssim.task.infrastructure.outbox.OutboxPublisherScheduler;
import com.chenmingqiang.wirelesssim.task.infrastructure.redis.TaskDetailCache;
import com.chenmingqiang.wirelesssim.task.infrastructure.redis.TaskSubmissionRateLimiter;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/**
 * 阶段8真实端到端验收测试。
 *
 * <p>该测试不Mock数据库、Redis、RabbitMQ或Worker，验证“任务提交、Outbox可靠发布、
 * RabbitMQ消费、Java模拟执行、结果落库、缓存失效”整个链路的最终一致性。</p>
 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=true",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.messaging.consumer-enabled=true",
        "simulation.outbox.enabled=true",
        "simulation.outbox.scan-interval=1h",
        "simulation.outbox.batch-size=1",
        "simulation.execution.step-delay-ms=0",
        "simulation.execution.recovery-scan-interval-ms=3600000",
        "simulation.redis.enabled=true"
})
class StageEightEndToEndIT {

    /** 创建任务并读取最终任务详情。 */
    @Autowired
    private TaskService taskService;
    /** 手动触发一次Outbox发布，避免等待定时任务。 */
    @Autowired
    private OutboxPublisherScheduler outboxPublisherScheduler;
    /** 查询发布前后的Outbox业务事件。 */
    @Autowired
    private OutboxEventMapper outboxEventMapper;
    /** 准备和检查MySQL中的跨表状态。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 检查缓存是否在任务状态变化后失效。 */
    @Autowired
    private TaskDetailCache taskDetailCache;
    /** 检查和清理Redis测试键。 */
    @Autowired
    private StringRedisTemplate redisTemplate;
    /** 清理真实RabbitMQ队列并检查消息已被消费。 */
    @Autowired
    private RabbitTemplate rabbitTemplate;
    /** 序列化合法无线通信场景配置。 */
    @Autowired
    private ObjectMapper objectMapper;

    /** 当前测试创建的用户，作为所有测试数据的清理边界。 */
    private Long userId;
    /** 当前测试提交的任务。 */
    private Long taskId;

    /** 测试开始前清除三个阶段8队列中的历史消息。 */
    @BeforeEach
    void purgeQueues() {
        purgeAllQueues();
    }

    /** 按外键依赖顺序清理MySQL、Redis和RabbitMQ测试数据。 */
    @AfterEach
    void cleanUp() {
        purgeAllQueues();
        if (userId == null) {
            return;
        }
        redisTemplate.delete(TaskSubmissionRateLimiter.KEY_PREFIX + userId);
        if (taskId != null) {
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

    /**
     * 验证阶段8全部组件协作后，四张业务表、RabbitMQ队列和Redis缓存处于一致状态。
     */
    @Test
    void taskSubmissionEventuallyProducesOneConsistentResult() throws Exception {
        long scenarioId = createFixture();
        TaskResponse submitted = taskService.submit(
                userId,
                "stage-eight-e2e-" + UUID.randomUUID(),
                taskRequest(scenarioId)
        );
        taskId = submitted.id();
        OutboxEvent pendingEvent = outboxEventMapper.findByBusinessKey(
                "EXPERIMENT_TASK",
                taskId,
                "TASK_EXECUTION_REQUESTED",
                1
        );

        assertThat(submitted.status()).isEqualTo(TaskStatus.PENDING);
        assertThat(pendingEvent.getStatus()).isEqualTo(OutboxStatus.PENDING);
        taskService.get(userId, taskId);
        String cacheKey = taskDetailCache.key(userId, taskId);
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNotNull();

        outboxPublisherScheduler.publishOnce();
        await(() -> "SUCCEEDED".equals(taskStatus()), "端到端任务未在期限内执行成功");
        await(() -> redisTemplate.opsForValue().get(cacheKey) == null, "任务状态变化后缓存未失效");
        await(() -> queueMessageCount(SimulationRabbitNames.EXECUTE_QUEUE) == 0,
                "执行消息未被消费者确认");

        OutboxEvent publishedEvent = outboxEventMapper.findByEventId(pendingEvent.getEventId());
        Map<String, Object> task = jdbcTemplate.queryForMap(
                "SELECT status, progress, retry_count, error_message FROM experiment_task WHERE id=?",
                taskId
        );
        Map<String, Object> execution = jdbcTemplate.queryForMap(
                "SELECT attempt_no, status, heartbeat_at, started_at, finished_at "
                        + "FROM task_execution WHERE task_id=?",
                taskId
        );
        Map<String, Object> result = jdbcTemplate.queryForMap(
                "SELECT throughput, average_aoi, convergence_step, metrics_json "
                        + "FROM simulation_result WHERE task_id=?",
                taskId
        );

        assertThat(publishedEvent.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
        assertThat(publishedEvent.getPublishAttempts()).isEqualTo(1);
        assertThat(publishedEvent.getPublishedAt()).isNotNull();
        assertThat(task.get("status")).isEqualTo("SUCCEEDED");
        assertThat(((Number) task.get("progress")).intValue()).isEqualTo(100);
        assertThat(((Number) task.get("retry_count")).intValue()).isZero();
        assertThat(task.get("error_message")).isNull();
        assertThat(((Number) execution.get("attempt_no")).intValue()).isEqualTo(1);
        assertThat(execution.get("status")).isEqualTo("SUCCEEDED");
        assertThat(execution.get("heartbeat_at")).isNotNull();
        assertThat(execution.get("started_at")).isNotNull();
        assertThat(execution.get("finished_at")).isNotNull();
        assertThat(result.get("throughput")).isNotNull();
        assertThat(result.get("average_aoi")).isNotNull();
        assertThat(result.get("convergence_step")).isNotNull();
        assertThat(result.get("metrics_json")).isNotNull();
        assertThat(count("task_execution")).isEqualTo(1);
        assertThat(count("simulation_result")).isEqualTo(1);

        TaskResponse finalResponse = taskService.get(userId, taskId);
        assertThat(finalResponse.status()).isEqualTo(TaskStatus.SUCCEEDED);
        assertThat(finalResponse.progress()).isEqualTo(100);
        assertThat(redisTemplate.opsForValue().get(cacheKey)).isNotNull();
    }

    /** 创建相互关联的测试用户与无线通信仿真场景。 */
    private long createFixture() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "stage8_e2e_" + suffix.substring(0, 12);
        jdbcTemplate.update(
                "INSERT INTO app_user(username,password_hash,role,status) VALUES(?,?,'USER','ACTIVE')",
                username,
                "test-password-hash"
        );
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username=?",
                Long.class,
                username
        );
        ScenarioConfigRequest config = new ScenarioConfigRequest(
                8, 4, 1000,
                new BigDecimal("0.25"),
                new BigDecimal("5.0"),
                new BigDecimal("20.0"),
                new BigDecimal("100.0"),
                new BigDecimal("10.0"),
                new BigDecimal("2.0"),
                AccessScheme.RSMA,
                42L
        );
        String scenarioName = "阶段8端到端场景-" + suffix;
        jdbcTemplate.update(
                "INSERT INTO simulation_scenario(owner_id,name,description,objective,config_json) "
                        + "VALUES(?,?,?,'THROUGHPUT',CAST(? AS JSON))",
                userId,
                scenarioName,
                "验证Outbox、RabbitMQ、Worker、Redis完整链路",
                objectMapper.writeValueAsString(config)
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM simulation_scenario WHERE owner_id=? AND name=?",
                Long.class,
                userId,
                scenarioName
        );
    }

    /** 使用固定种子构造可重复的GRPO模拟任务。 */
    private CreateTaskRequest taskRequest(long scenarioId) {
        return new CreateTaskRequest(
                scenarioId,
                TaskAlgorithm.GRPO,
                new TrainingConfigRequest(
                        1000,
                        new BigDecimal("0.0003"),
                        64,
                        new BigDecimal("0.99"),
                        20260720L
                ),
                5
        );
    }

    /** 查询任务事实表的当前状态。 */
    private String taskStatus() {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id=?",
                String.class,
                taskId
        );
    }

    /** 统计指定任务在从表中的记录数量。 */
    private int count(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE task_id=?",
                Integer.class,
                taskId
        );
    }

    /** 返回指定RabbitMQ队列的待消费消息数。 */
    private long queueMessageCount(String queue) {
        return rabbitTemplate.execute(channel -> channel.messageCount(queue));
    }

    /** 清空正式、重试和死信队列，防止其他集成测试消息相互影响。 */
    private void purgeAllQueues() {
        rabbitTemplate.execute(channel -> {
            channel.queuePurge(SimulationRabbitNames.EXECUTE_QUEUE);
            channel.queuePurge(SimulationRabbitNames.RETRY_QUEUE);
            channel.queuePurge(SimulationRabbitNames.DEAD_LETTER_QUEUE);
            return null;
        });
    }

    /** 最多等待5秒并高频检查异步状态，避免固定休眠拖慢测试。 */
    private void await(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(25);
        }
        throw new AssertionError(failureMessage);
    }
}
