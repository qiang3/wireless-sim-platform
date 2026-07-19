package com.chenmingqiang.wirelesssim.task.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用真实MySQL验证消息轮次判断和首次任务入队。 */
@SpringBootTest(properties = "simulation.execution.enabled=false")
class TaskMessagePreparationServiceIT {

    @Autowired
    private TaskMessagePreparationService service;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long userId;

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM task_execution WHERE task_id IN "
                + "(SELECT id FROM experiment_task WHERE creator_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM experiment_task WHERE creator_id = ?", userId);
        jdbcTemplate.update("DELETE FROM simulation_scenario WHERE owner_id = ?", userId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    @Test
    void firstAttemptMovesPendingTaskToQueued() {
        long taskId = insertTask("PENDING", 0);

        TaskMessagePreparationResult result = service.prepare(taskId, 1);

        assertThat(result.outcome()).isEqualTo(TaskMessagePreparationOutcome.READY_TO_EXECUTE);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id = ?", String.class, taskId))
                .isEqualTo("QUEUED");
    }

    @Test
    void staleAndFutureAttemptsAreDistinguished() {
        long taskId = insertTask("QUEUED", 1);

        assertThat(service.prepare(taskId, 1).outcome())
                .isEqualTo(TaskMessagePreparationOutcome.STALE_ATTEMPT);
        assertThat(service.prepare(taskId, 3).outcome())
                .isEqualTo(TaskMessagePreparationOutcome.FUTURE_ATTEMPT);
        assertThat(service.prepare(taskId, 2).outcome())
                .isEqualTo(TaskMessagePreparationOutcome.READY_TO_EXECUTE);
    }

    @Test
    void cancelledTaskIsAlreadyHandledAndMissingTaskIsReported() {
        long taskId = insertTask("CANCELLED", 0);

        assertThat(service.prepare(taskId, 1).outcome())
                .isEqualTo(TaskMessagePreparationOutcome.ALREADY_HANDLED);
        assertThat(service.prepare(Long.MAX_VALUE, 1).outcome())
                .isEqualTo(TaskMessagePreparationOutcome.TASK_NOT_FOUND);
    }

    /** 创建最小但满足外键和非空约束的任务。 */
    private long insertTask(String status, int retryCount) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "prepare_" + suffix.substring(0, 12);
        jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash) VALUES (?, ?)",
                username,
                "test-password-hash"
        );
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);

        jdbcTemplate.update("""
                INSERT INTO simulation_scenario (owner_id, name, objective, config_json)
                VALUES (?, '消息准备测试场景', 'THROUGHPUT', JSON_OBJECT('seed', 42))
                """, userId);
        Long scenarioId = jdbcTemplate.queryForObject(
                "SELECT id FROM simulation_scenario WHERE owner_id = ?", Long.class, userId);

        String taskNo = suffix.substring(0, 32);
        jdbcTemplate.update("""
                INSERT INTO experiment_task (
                    task_no, scenario_id, scenario_snapshot_json, creator_id,
                    algorithm, training_config_json, priority, status, retry_count,
                    idempotency_key, request_hash, submitted_at
                ) VALUES (
                    ?, ?, JSON_OBJECT('seed', 42), ?,
                    'GRPO', JSON_OBJECT('seed', 42), 5, ?, ?,
                    ?, ?, CURRENT_TIMESTAMP(3)
                )
                """,
                taskNo, scenarioId, userId, status, retryCount,
                "prepare-" + suffix, suffix + suffix
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM experiment_task WHERE task_no = ?", Long.class, taskNo);
    }
}
