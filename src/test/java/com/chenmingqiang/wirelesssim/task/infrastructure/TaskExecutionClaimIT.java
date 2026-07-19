package com.chenmingqiang.wirelesssim.task.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.application.TaskExecutionClaimService;
import com.chenmingqiang.wirelesssim.task.domain.TaskExecution;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(properties = "simulation.execution.enabled=false")
class TaskExecutionClaimIT {

    @Autowired
    private TaskExecutionClaimService claimService;

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
    void concurrentWorkersCanCreateOnlyOneExecutionForSameAttempt() throws Exception {
        long taskId = insertPendingTask();
        assertThat(claimService.enqueuePendingTask(taskId)).isTrue();
        assertThat(claimService.enqueuePendingTask(taskId)).isFalse();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<TaskExecution>> first = executor.submit(
                    () -> awaitAndClaim(taskId, "test-worker-1", ready, start));
            Future<Optional<TaskExecution>> second = executor.submit(
                    () -> awaitAndClaim(taskId, "test-worker-2", ready, start));

            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            List<Optional<TaskExecution>> outcomes = List.of(
                    first.get(10, TimeUnit.SECONDS),
                    second.get(10, TimeUnit.SECONDS)
            );
            assertThat(outcomes).filteredOn(Optional::isPresent).hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id = ?", String.class, taskId))
                .isEqualTo("RUNNING");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT lock_version FROM experiment_task WHERE id = ?", Integer.class, taskId))
                .isEqualTo(2);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT attempt_no FROM task_execution WHERE task_id = ?", Integer.class, taskId))
                .isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM task_execution WHERE task_id = ?", String.class, taskId))
                .isEqualTo("RUNNING");
    }

    @Test
    void strictAttemptClaimRejectsOldMessageAndAllowsOnlyOneConcurrentConsumer() throws Exception {
        long taskId = insertPendingTask();
        assertThat(claimService.enqueuePendingTask(taskId)).isTrue();

        // 当前retry_count为0，只允许attemptNo=1；旧/未来轮次都不能改变任务。
        assertThat(claimService.claimQueuedTask(taskId, 2, "future-worker")).isEmpty();

        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<Optional<TaskExecution>> first = executor.submit(
                    () -> awaitAndStrictClaim(taskId, 1, "rabbit-worker-1", ready, start));
            Future<Optional<TaskExecution>> second = executor.submit(
                    () -> awaitAndStrictClaim(taskId, 1, "rabbit-worker-2", ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .filteredOn(Optional::isPresent)
                    .hasSize(1);
        } finally {
            executor.shutdownNow();
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE task_id = ? AND attempt_no = 1",
                Integer.class,
                taskId
        )).isEqualTo(1);
    }

    private Optional<TaskExecution> awaitAndClaim(
            long taskId,
            String workerId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("并发抢占测试启动超时");
        }
        return claimService.claimQueuedTask(taskId, workerId);
    }

    /** 让两个线程同时调用带attemptNo的严格抢占入口。 */
    private Optional<TaskExecution> awaitAndStrictClaim(
            long taskId,
            int attemptNo,
            String workerId,
            CountDownLatch ready,
            CountDownLatch start
    ) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("严格并发抢占测试启动超时");
        }
        return claimService.claimQueuedTask(taskId, attemptNo, workerId);
    }

    private long insertPendingTask() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbcTemplate.update(
                "INSERT INTO app_user (username, password_hash) VALUES (?, ?)",
                "claim_" + suffix.substring(0, 12),
                "test-password-hash"
        );
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?",
                Long.class,
                "claim_" + suffix.substring(0, 12)
        );

        jdbcTemplate.update("""
                INSERT INTO simulation_scenario (
                    owner_id, name, objective, config_json
                ) VALUES (?, ?, 'THROUGHPUT', JSON_OBJECT('seed', 42))
                """, userId, "并发抢占测试场景");
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
                    ?, ?, JSON_OBJECT('seed', 42), ?,
                    'GRPO', JSON_OBJECT('seed', 42), 5, 'PENDING',
                    ?, ?, CURRENT_TIMESTAMP(3)
                )
                """,
                taskNo,
                scenarioId,
                userId,
                "claim-" + suffix,
                suffix + suffix
        );
        return jdbcTemplate.queryForObject(
                "SELECT id FROM experiment_task WHERE task_no = ?",
                Long.class,
                taskNo
        );
    }
}
