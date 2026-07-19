package com.chenmingqiang.wirelesssim.task.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

/** 使用真实MySQL验证重试耗尽只修改匹配轮次的待执行任务。 */
@SpringBootTest(properties = "simulation.execution.enabled=false")
class TaskMessageFailureServiceIT {

    @Autowired
    private TaskMessageFailureService service;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    private Long userId;

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM experiment_task WHERE creator_id = ?", userId);
        jdbcTemplate.update("DELETE FROM simulation_scenario WHERE owner_id = ?", userId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
    }

    @Test
    void matchingPendingTaskIsMarkedFailed() {
        long taskId = insertTask("PENDING", 0);

        assertThat(service.markDeliveryExhausted(taskId, 1, "database timeout")).isTrue();
        assertThat(status(taskId)).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM experiment_task WHERE id = ?", String.class, taskId))
                .contains("消息处理重试耗尽", "database timeout");
    }

    @Test
    void wrongBusinessAttemptDoesNotOverwriteTask() {
        long taskId = insertTask("QUEUED", 1);

        assertThat(service.markDeliveryExhausted(taskId, 1, "old message")).isFalse();
        assertThat(status(taskId)).isEqualTo("QUEUED");
    }

    @Test
    void terminalTaskIsNeverOverwritten() {
        long taskId = insertTask("SUCCEEDED", 0);

        assertThat(service.markDeliveryExhausted(taskId, 1, "late failure")).isFalse();
        assertThat(status(taskId)).isEqualTo("SUCCEEDED");
    }

    private long insertTask(String status, int retryCount) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "msgfail_" + suffix.substring(0, 12);
        jdbcTemplate.update("INSERT INTO app_user (username, password_hash) VALUES (?, ?)",
                username, "test-password-hash");
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);
        jdbcTemplate.update("""
                INSERT INTO simulation_scenario (owner_id, name, objective, config_json)
                VALUES (?, '消息失败测试场景', 'THROUGHPUT', JSON_OBJECT('seed', 42))
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
                    ?, ?, JSON_OBJECT('seed', 42), ?, 'GRPO', JSON_OBJECT('seed', 42),
                    5, ?, ?, ?, ?, CURRENT_TIMESTAMP(3)
                )
                """, taskNo, scenarioId, userId, status, retryCount,
                "msgfail-" + suffix, suffix + suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM experiment_task WHERE task_no = ?", Long.class, taskNo);
    }

    private String status(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id = ?", String.class, taskId);
    }
}
