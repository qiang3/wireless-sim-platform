package com.chenmingqiang.wirelesssim.task.infrastructure.execution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import com.chenmingqiang.wirelesssim.task.api.TaskActionRequest;
import com.chenmingqiang.wirelesssim.task.application.TaskResultService;
import com.chenmingqiang.wirelesssim.task.application.TaskService;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.TaskExecutionMessageListener;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = {
        "simulation.execution.enabled=true",
        "simulation.execution.dispatch-interval-ms=60000",
        "simulation.execution.step-delay-ms=50"
})
class SimulationTaskDispatcherIT {

    @Autowired
    private SimulationTaskDispatcher dispatcher;

    @Autowired
    private TaskService taskService;

    @Autowired
    private TaskResultService taskResultService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ApplicationContext applicationContext;

    private Long userId;
    private Long otherUserId;

    @Test
    void mysqlModeCreatesDispatcherButNotRabbitConsumer() {
        assertThat(applicationContext.getBeansOfType(SimulationTaskDispatcher.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(TaskExecutionMessageListener.class)).isEmpty();
    }

    @AfterEach
    void cleanUp() {
        if (userId == null) {
            return;
        }
        jdbcTemplate.update("DELETE FROM simulation_result WHERE task_id IN "
                + "(SELECT id FROM experiment_task WHERE creator_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM task_execution WHERE task_id IN "
                + "(SELECT id FROM experiment_task WHERE creator_id = ?)", userId);
        jdbcTemplate.update("DELETE FROM experiment_task WHERE creator_id = ?", userId);
        jdbcTemplate.update("DELETE FROM simulation_scenario WHERE owner_id = ?", userId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", userId);
        if (otherUserId != null) {
            jdbcTemplate.update("DELETE FROM app_user WHERE id = ?", otherUserId);
        }
    }

    @Test
    void dispatcherEnqueuesAndRunsTaskOnDedicatedWorkerThreadOnlyOnce() throws Exception {
        long taskId = insertPendingTask();

        dispatcher.dispatchOnce();
        dispatcher.dispatchOnce();

        awaitTaskStatus(taskId, "SUCCEEDED");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE task_id = ?",
                Integer.class,
                taskId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT worker_id FROM task_execution WHERE task_id = ?",
                String.class,
                taskId
        )).contains("simulation-worker-");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_no FROM task_execution WHERE task_id = ?",
                Integer.class,
                taskId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM task_execution WHERE task_id = ?",
                String.class,
                taskId
        )).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT heartbeat_at IS NOT NULL FROM task_execution WHERE task_id = ?",
                Boolean.class,
                taskId
        )).isTrue();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id = ?",
                String.class,
                taskId
        )).isEqualTo("SUCCEEDED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM simulation_result WHERE task_id = ?",
                Integer.class,
                taskId
        )).isEqualTo(1);
        assertThat(taskResultService.getOwnedResult(userId, taskId).simulationMode())
                .isEqualTo("JAVA_MOCK");
        assertThat(taskResultService.getOwnedResult(userId, taskId).scientificResult())
                .isFalse();

        otherUserId = insertOtherUser();
        assertThatThrownBy(() -> taskResultService.getOwnedResult(otherUserId, taskId))
                .isInstanceOf(BusinessException.class)
                .extracting("code")
                .isEqualTo("TASK_RESULT_NOT_FOUND");
    }

    @Test
    void runningCancellationStopsWorkerAndMarksExecutionCancelled() throws Exception {
        long taskId = insertPendingTask();

        dispatcher.dispatchOnce();
        awaitProgressAtLeast(taskId, 10);

        Integer version = jdbcTemplate.queryForObject(
                "SELECT lock_version FROM experiment_task WHERE id = ?",
                Integer.class,
                taskId
        );
        taskService.cancel(userId, taskId, new TaskActionRequest(version));

        awaitExecutionStatus(taskId, "CANCELLED");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id = ?",
                String.class,
                taskId
        )).isEqualTo("CANCELLED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT progress FROM experiment_task WHERE id = ?",
                Integer.class,
                taskId
        )).isLessThan(100);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT finished_at IS NOT NULL FROM task_execution WHERE task_id = ?",
                Boolean.class,
                taskId
        )).isTrue();
    }

    @Test
    void workerFailureCanBeRepairedAndRetriedAsSecondAttempt() throws Exception {
        long taskId = insertPendingTask(false);

        dispatcher.dispatchOnce();
        awaitTaskStatus(taskId, "FAILED");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM task_execution WHERE task_id = ? AND attempt_no = 1",
                String.class,
                taskId
        )).isEqualTo("FAILED");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM simulation_result WHERE task_id = ?",
                Integer.class,
                taskId
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT error_message FROM experiment_task WHERE id = ?",
                String.class,
                taskId
        )).isNotBlank();

        repairTrainingConfig(taskId);
        Integer version = jdbcTemplate.queryForObject(
                "SELECT lock_version FROM experiment_task WHERE id = ?",
                Integer.class,
                taskId
        );
        taskService.retry(userId, taskId, new TaskActionRequest(version));
        dispatcher.dispatchOnce();
        awaitTaskStatus(taskId, "SUCCEEDED");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT retry_count FROM experiment_task WHERE id = ?",
                Integer.class,
                taskId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForList(
                "SELECT status FROM task_execution WHERE task_id = ? ORDER BY attempt_no",
                String.class,
                taskId
        )).containsExactly("FAILED", "SUCCEEDED");
        assertThat(jdbcTemplate.queryForList(
                "SELECT attempt_no FROM task_execution WHERE task_id = ? ORDER BY attempt_no",
                Integer.class,
                taskId
        )).containsExactly(1, 2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM simulation_result WHERE task_id = ?",
                Integer.class,
                taskId
        )).isEqualTo(1);
    }

    private void awaitProgressAtLeast(long taskId, int expectedProgress) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            Integer progress = jdbcTemplate.queryForObject(
                    "SELECT progress FROM experiment_task WHERE id = ?",
                    Integer.class,
                    taskId
            );
            if (progress != null && progress >= expectedProgress) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待任务进度超时，expectedProgress=" + expectedProgress);
    }

    private void awaitExecutionStatus(long taskId, String expectedStatus) throws InterruptedException {
        for (int attempt = 0; attempt < 100; attempt++) {
            String actualStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM task_execution WHERE task_id = ?",
                    String.class,
                    taskId
            );
            if (expectedStatus.equals(actualStatus)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待执行记录状态超时，expectedStatus=" + expectedStatus);
    }

    private void awaitTaskStatus(long taskId, String expectedStatus) throws InterruptedException {
        for (int attempt = 0; attempt < 120; attempt++) {
            String actualStatus = jdbcTemplate.queryForObject(
                    "SELECT status FROM experiment_task WHERE id = ?",
                    String.class,
                    taskId
            );
            if (expectedStatus.equals(actualStatus)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("等待任务状态超时，expectedStatus=" + expectedStatus);
    }

    private long insertPendingTask() {
        return insertPendingTask(true);
    }

    private long insertPendingTask(boolean validTrainingConfig) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "dispatcher_" + suffix.substring(0, 10);
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

        String trainingConfigJson = validTrainingConfig
                ? validTrainingConfigJson()
                : "{\"invalid\":true}";
        jdbcTemplate.update("""
                INSERT INTO simulation_scenario (
                    owner_id, name, objective, config_json
                ) VALUES (?, ?, 'THROUGHPUT', JSON_OBJECT('seed', 42))
                """, userId, "调度器测试场景");
        Long scenarioId = jdbcTemplate.queryForObject(
                "SELECT id FROM simulation_scenario WHERE owner_id = ?",
                Long.class,
                userId
        );

        String taskNo = suffix.substring(0, 32);
        jdbcTemplate.update("""
                INSERT INTO experiment_task (
                    task_no, scenario_id, scenario_snapshot_json, creator_id,
                    algorithm, training_config_json, priority, status,
                    idempotency_key, request_hash, submitted_at
                ) VALUES (
                    ?, ?, JSON_OBJECT(
                        'scenarioName', '调度器测试场景',
                        'description', '仅用于集成测试',
                        'objective', 'THROUGHPUT',
                        'config', JSON_OBJECT(
                            'deviceCount', 8,
                            'antennaCount', 4,
                            'timeSlotCount', 1000,
                            'dataArrivalRate', 0.25,
                            'averageGreenEnergy', 5.0,
                            'batteryCapacity', 20.0,
                            'dataBufferCapacity', 100.0,
                            'wptTransmitPower', 10.0,
                            'deviceMaxTransmitPower', 2.0,
                            'accessScheme', 'RSMA',
                            'randomSeed', 42
                        ),
                        'version', 0
                    ), ?,
                    'GRPO', CAST(? AS JSON), 5, 'PENDING',
                    ?, ?, CURRENT_TIMESTAMP(3)
                )
                """,
                taskNo,
                scenarioId,
                userId,
                trainingConfigJson,
                "dispatcher-" + suffix,
                suffix + suffix
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM experiment_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
    }

    private void repairTrainingConfig(long taskId) {
        jdbcTemplate.update(
                "UPDATE experiment_task SET training_config_json = CAST(? AS JSON) WHERE id = ?",
                validTrainingConfigJson(),
                taskId
        );
    }

    private String validTrainingConfigJson() {
        return """
                {
                  "maxTrainingSteps": 1000,
                  "learningRate": 0.0003,
                  "batchSize": 64,
                  "discountFactor": 0.99,
                  "randomSeed": 2025
                }
                """;
    }

    private long insertOtherUser() {
        String username = "result_other_" + UUID.randomUUID().toString().substring(0, 8);
        jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash) VALUES (?, ?)",
                username,
                "test-password-hash"
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                Long.class,
                username
        );
    }
}
