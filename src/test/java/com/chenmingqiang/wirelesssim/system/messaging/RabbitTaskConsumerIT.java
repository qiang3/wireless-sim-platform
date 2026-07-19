package com.chenmingqiang.wirelesssim.system.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.task.infrastructure.execution.SimulationTaskDispatcher;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.SimulationRabbitNames;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.TaskExecutionMessageListener;
import com.chenmingqiang.wirelesssim.task.infrastructure.messaging.TaskExecutionRequestedMessage;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

/** 使用真实MySQL和RabbitMQ验收“发布、消费、Worker、结果、手动ACK”完整链路。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=true",
        "simulation.execution.dispatch-mode=rabbitmq",
        "simulation.messaging.consumer-enabled=true",
        "simulation.outbox.enabled=false",
        "simulation.execution.step-delay-ms=0",
        "simulation.execution.recovery-scan-interval-ms=3600000"
})
class RabbitTaskConsumerIT {

    @Autowired
    private RabbitTemplate rabbitTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private ApplicationContext applicationContext;

    private Long userId;

    @BeforeEach
    void purgeQueue() {
        rabbitTemplate.execute(channel -> channel.queuePurge(SimulationRabbitNames.EXECUTE_QUEUE));
    }

    @AfterEach
    void cleanUp() {
        rabbitTemplate.execute(channel -> channel.queuePurge(SimulationRabbitNames.EXECUTE_QUEUE));
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
    }

    @Test
    void validAndDuplicateMessagesCreateOnlyOneExecutionAndOneResult() throws Exception {
        long taskId = insertTask(true);
        Message message = executionMessage(taskId, 1);

        publish(message);
        await(() -> "SUCCEEDED".equals(taskStatus(taskId)), "合法消息未在期限内执行成功");

        publish(executionMessage(taskId, 1));
        await(() -> queueMessageCount() == 0, "重复消息未被ACK");

        assertThat(count("task_execution", taskId)).isEqualTo(1);
        assertThat(count("simulation_result", taskId)).isEqualTo(1);
        assertThat(applicationContext.getBeansOfType(TaskExecutionMessageListener.class)).hasSize(1);
        assertThat(applicationContext.getBeansOfType(SimulationTaskDispatcher.class)).isEmpty();
    }

    @Test
    void workerBusinessFailureIsPersistedThenMessageIsAcked() throws Exception {
        long taskId = insertTask(false);

        publish(executionMessage(taskId, 1));
        await(() -> "FAILED".equals(taskStatus(taskId)), "Worker业务失败未可靠落库");
        await(() -> queueMessageCount() == 0, "业务失败消息未被ACK");

        assertThat(count("task_execution", taskId)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM task_execution WHERE task_id = ?", String.class, taskId))
                .isEqualTo("FAILED");
        assertThat(count("simulation_result", taskId)).isZero();
    }

    /** 发送到正式交换机和正式路由键。 */
    private void publish(Message message) {
        rabbitTemplate.send(
                SimulationRabbitNames.TASK_EXCHANGE,
                SimulationRabbitNames.EXECUTE_ROUTING_KEY,
                message
        );
    }

    /** 创建与Outbox发布器契约完全相同的持久化消息。 */
    private Message executionMessage(long taskId, int attemptNo) throws Exception {
        String eventId = UUID.randomUUID().toString();
        TaskExecutionRequestedMessage payload = new TaskExecutionRequestedMessage(
                eventId,
                taskId,
                attemptNo,
                "TASK_EXECUTION_REQUESTED",
                1,
                Instant.now()
        );
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(eventId);
        properties.setType("TASK_EXECUTION_REQUESTED");
        properties.setHeader("attemptNo", attemptNo);
        properties.setHeader("schemaVersion", 1);
        return new Message(objectMapper.writeValueAsBytes(payload), properties);
    }

    /** 插入可成功执行或故意使用非法训练JSON的任务。 */
    private long insertTask(boolean validTrainingConfig) {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        String username = "consumer_" + suffix.substring(0, 12);
        jdbcTemplate.update("INSERT INTO app_user (username, password_hash) VALUES (?, ?)",
                username, "test-password-hash");
        userId = jdbcTemplate.queryForObject(
                "SELECT id FROM app_user WHERE username = ?", Long.class, username);

        jdbcTemplate.update("""
                INSERT INTO simulation_scenario (owner_id, name, objective, config_json)
                VALUES (?, 'Rabbit消费者测试场景', 'THROUGHPUT', JSON_OBJECT('seed', 42))
                """, userId);
        Long scenarioId = jdbcTemplate.queryForObject(
                "SELECT id FROM simulation_scenario WHERE owner_id = ?", Long.class, userId);

        String trainingJson = validTrainingConfig ? """
                {"maxTrainingSteps":1000,"learningRate":0.0003,"batchSize":64,
                 "discountFactor":0.99,"randomSeed":2025}
                """ : "{\"invalid\":true}";
        String taskNo = suffix.substring(0, 32);
        jdbcTemplate.update("""
                INSERT INTO experiment_task (
                    task_no, scenario_id, scenario_snapshot_json, creator_id,
                    algorithm, training_config_json, priority, status,
                    idempotency_key, request_hash, submitted_at
                ) VALUES (
                    ?, ?, JSON_OBJECT(
                        'scenarioName', 'Rabbit消费者测试场景',
                        'description', '阶段8.5集成测试',
                        'objective', 'THROUGHPUT',
                        'config', JSON_OBJECT(
                            'deviceCount', 8, 'antennaCount', 4, 'timeSlotCount', 1000,
                            'dataArrivalRate', 0.25, 'averageGreenEnergy', 5.0,
                            'batteryCapacity', 20.0, 'dataBufferCapacity', 100.0,
                            'wptTransmitPower', 10.0, 'deviceMaxTransmitPower', 2.0,
                            'accessScheme', 'RSMA', 'randomSeed', 42
                        ),
                        'version', 0
                    ), ?, 'GRPO', CAST(? AS JSON), 5, 'PENDING',
                    ?, ?, CURRENT_TIMESTAMP(3)
                )
                """,
                taskNo, scenarioId, userId, trainingJson,
                "consumer-" + suffix, suffix + suffix);
        return jdbcTemplate.queryForObject(
                "SELECT id FROM experiment_task WHERE task_no = ?", Long.class, taskNo);
    }

    private String taskStatus(long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id = ?", String.class, taskId);
    }

    private int count(String table, long taskId) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE task_id = ?", Integer.class, taskId);
    }

    private long queueMessageCount() {
        return rabbitTemplate.execute(channel ->
                channel.messageCount(SimulationRabbitNames.EXECUTE_QUEUE));
    }

    /** 最多等待5秒，避免用固定长时间sleep拖慢测试。 */
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
