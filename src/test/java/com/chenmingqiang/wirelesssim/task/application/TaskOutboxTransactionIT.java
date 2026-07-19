package com.chenmingqiang.wirelesssim.task.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import com.chenmingqiang.wirelesssim.task.api.CreateTaskRequest;
import com.chenmingqiang.wirelesssim.task.api.TaskActionRequest;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import com.chenmingqiang.wirelesssim.task.domain.OutboxEvent;
import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.task.infrastructure.OutboxEventMapper;
import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * 使用真实MySQL和真实TaskService证明任务与Outbox的事务原子性。
 *
 * <p>测试只把OutboxEventMapper替换成会主动失败的Mock。这样可以精确模拟
 * “前面的任务SQL已经执行，但后面的Outbox SQL失败”，再在事务结束后检查数据库是否回滚。</p>
 */
@SpringBootTest(properties = "simulation.execution.enabled=false")
class TaskOutboxTransactionIT {

    /** 被测试的真实任务应用服务，调用时会经过Spring事务代理。 */
    @Autowired
    private TaskService taskService;
    /** 用于准备前置数据，并在事务结束后读取真实数据库状态。 */
    @Autowired
    private JdbcTemplate jdbcTemplate;
    /** 用于生成合法的场景配置JSON。 */
    @Autowired
    private ObjectMapper objectMapper;

    /**
     * 替换真实Outbox Mapper的测试Bean。
     * 任务Mapper和事务管理器仍然是真实对象，因此能够验证MySQL回滚。
     */
    @MockitoBean
    private OutboxEventMapper outboxEventMapper;

    /** 当前测试用户ID。 */
    private Long userId;
    /** 当前测试场景ID。 */
    private Long scenarioId;

    /** 每项测试前创建互相隔离的用户和合法场景。 */
    @BeforeEach
    void setUp() throws JacksonException {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "outbox_tx_" + suffix.substring(0, 12);
        jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash) VALUES (?, ?)",
                username,
                "test-password-hash"
        );
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                Long.class,
                username
        );

        String configJson = objectMapper.writeValueAsString(validScenarioConfig());
        jdbcTemplate.update("""
                INSERT INTO simulation_scenario (
                    owner_id, name, description, objective, config_json
                ) VALUES (?, ?, ?, 'THROUGHPUT', CAST(? AS JSON))
                """, userId, "Outbox事务测试场景", "验证任务和事件同时回滚", configJson);
        scenarioId = jdbcTemplate.queryForObject(
                "SELECT id FROM simulation_scenario WHERE owner_id = ?",
                Long.class,
                userId
        );

        // 让TaskService在前面的任务SQL执行后，于Outbox插入位置抛出运行时异常。
        when(outboxEventMapper.insertPending(any(OutboxEvent.class)))
                .thenThrow(new IllegalStateException("测试故障：Outbox插入失败"));
    }

    /** 清理本测试产生的数据；删除顺序遵守任务相关表的依赖关系。 */
    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_type='EXPERIMENT_TASK' "
                + "AND aggregate_id IN (SELECT id FROM experiment_task WHERE creator_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM simulation_result WHERE task_id IN "
                + "(SELECT id FROM experiment_task WHERE creator_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM task_execution WHERE task_id IN "
                + "(SELECT id FROM experiment_task WHERE creator_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM experiment_task WHERE creator_id = ?", userId);
        jdbcTemplate.update("DELETE FROM simulation_scenario WHERE owner_id = ?", userId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    /** Outbox插入失败时，首次提交已经执行的任务INSERT也必须回滚。 */
    @Test
    void submitRollsBackTaskWhenOutboxInsertFails() {
        assertThatThrownBy(() -> taskService.submit(
                userId,
                "rollback-submit-" + UUID.randomUUID(),
                validTaskRequest()
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("测试故障：Outbox插入失败");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM experiment_task WHERE creator_id = ?",
                Integer.class,
                userId
        )).isZero();

        ArgumentCaptor<OutboxEvent> eventCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventMapper).insertPending(eventCaptor.capture());
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE event_id = ?",
                Integer.class,
                eventCaptor.getValue().getEventId()
        )).isZero();
    }

    /** Outbox插入失败时，人工重试对状态、次数、版本和错误信息的更新必须全部回滚。 */
    @Test
    void retryRollsBackTaskStateWhenOutboxInsertFails() {
        long taskId = insertFailedTask();

        assertThatThrownBy(() -> taskService.retry(userId, taskId, new TaskActionRequest(0)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("测试故障：Outbox插入失败");

        Map<String, Object> task = jdbcTemplate.queryForMap("""
                SELECT status, retry_count, lock_version, error_message
                FROM experiment_task
                WHERE id = ?
                """, taskId);
        assertThat(task.get("status")).isEqualTo("FAILED");
        assertThat(((Number) task.get("retry_count")).intValue()).isZero();
        assertThat(((Number) task.get("lock_version")).intValue()).isZero();
        assertThat(task.get("error_message")).isEqualTo("原始执行失败");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?",
                Integer.class,
                taskId
        )).isZero();
    }

    /** 直接插入一条FAILED任务，作为人工重试回滚测试的前置状态。 */
    private long insertFailedTask() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String taskNo = suffix.substring(0, 32);
        jdbcTemplate.update("""
                INSERT INTO experiment_task (
                    task_no, scenario_id, scenario_snapshot_json, creator_id,
                    algorithm, training_config_json, priority, status,
                    retry_count, idempotency_key, request_hash,
                    error_message, lock_version, submitted_at, finished_at
                ) VALUES (
                    ?, ?, JSON_OBJECT('scenarioName', '事务回滚测试'), ?,
                    'GRPO', JSON_OBJECT('randomSeed', 42), 5, 'FAILED',
                    0, ?, ?,
                    '原始执行失败', 0, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3)
                )
                """,
                taskNo,
                scenarioId,
                userId,
                "retry-rollback-" + suffix,
                suffix + suffix
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM experiment_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
    }

    /** 返回能够被TaskService解析的完整无线通信场景配置。 */
    private ScenarioConfigRequest validScenarioConfig() {
        return new ScenarioConfigRequest(
                3,
                4,
                10000,
                new BigDecimal("2.5"),
                new BigDecimal("5.0"),
                new BigDecimal("20.0"),
                new BigDecimal("1.5"),
                new BigDecimal("10.0"),
                new BigDecimal("1.0"),
                AccessScheme.RSMA,
                20260719L
        );
    }

    /** 返回合法任务提交参数，保证失败点只发生在Outbox Mapper。 */
    private CreateTaskRequest validTaskRequest() {
        return new CreateTaskRequest(
                scenarioId,
                TaskAlgorithm.GRPO,
                new TrainingConfigRequest(
                        100000,
                        new BigDecimal("0.0003"),
                        64,
                        new BigDecimal("0.99"),
                        20260719L
                ),
                5
        );
    }
}
