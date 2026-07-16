package com.chenmingqiang.wirelesssim.task.infrastructure.execution;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.application.TaskExecutionLifecycleService;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "simulation.execution.enabled=true",
        "simulation.execution.dispatch-interval-ms=60000",
        "simulation.execution.recovery-scan-interval-ms=60000",
        "simulation.execution.heartbeat-timeout-seconds=5"
})
class TaskTimeoutRecoveryIT {

    @Autowired
    private TaskTimeoutRecoveryScheduler recoveryScheduler;

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
    void staleRunningExecutionIsMarkedFailedTogetherWithTask() {
        long taskId = insertTimedOutRunningTask();

        recoveryScheduler.recoverOnce();

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id = ?",
                String.class,
                taskId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM task_execution WHERE task_id = ?",
                String.class,
                taskId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM experiment_task WHERE id = ?",
                String.class,
                taskId
        )).isEqualTo(TaskExecutionLifecycleService.HEARTBEAT_TIMEOUT_ERROR);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM task_execution WHERE task_id = ?",
                String.class,
                taskId
        )).isEqualTo(TaskExecutionLifecycleService.HEARTBEAT_TIMEOUT_ERROR);
    }

    private long insertTimedOutRunningTask() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "timeout_" + suffix.substring(0, 12);
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

        jdbcTemplate.update("""
                INSERT INTO simulation_scenario (
                    owner_id, name, objective, config_json
                ) VALUES (?, '超时恢复测试场景', 'THROUGHPUT', JSON_OBJECT('seed', 42))
                """, userId);
        Long scenarioId = jdbcTemplate.queryForObject(
                "SELECT id FROM simulation_scenario WHERE owner_id = ?",
                Long.class,
                userId
        );

        String taskNo = suffix.substring(0, 32);
        jdbcTemplate.update("""
                INSERT INTO experiment_task (
                    task_no, scenario_id, scenario_snapshot_json, creator_id,
                    algorithm, training_config_json, priority, status, progress,
                    idempotency_key, request_hash, submitted_at, started_at, lock_version
                ) VALUES (
                    ?, ?, JSON_OBJECT('seed', 42), ?,
                    'GRPO', JSON_OBJECT('seed', 42), 5, 'RUNNING', 40,
                    ?, ?, CURRENT_TIMESTAMP(3), CURRENT_TIMESTAMP(3), 2
                )
                """,
                taskNo,
                scenarioId,
                userId,
                "timeout-" + suffix,
                suffix + suffix
        );
        Long taskId = jdbcTemplate.queryForObject(
                "SELECT id FROM experiment_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
        jdbcTemplate.update("""
                INSERT INTO task_execution (
                    task_id, attempt_no, worker_id, status,
                    heartbeat_at, started_at
                ) VALUES (
                    ?, 1, 'lost-worker', 'RUNNING',
                    TIMESTAMPADD(SECOND, -10, CURRENT_TIMESTAMP(3)),
                    TIMESTAMPADD(SECOND, -20, CURRENT_TIMESTAMP(3))
                )
                """, taskId);
        return taskId;
    }
}
