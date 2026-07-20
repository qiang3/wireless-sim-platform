package com.chenmingqiang.wirelesssim.task.api.worker;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import tools.jackson.databind.ObjectMapper;

/** 使用真实MySQL验证Python Worker API的领取、重复消息和结果事务闭环。 */
@SpringBootTest(properties = {
        "simulation.execution.enabled=false",
        "simulation.redis.enabled=false",
        "simulation.worker-api.token=test-worker-token"
})
@AutoConfigureMockMvc
class PythonWorkerApiIT {

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private ObjectMapper objectMapper;
    private Long userId;

    @AfterEach
    void cleanUp() {
        if (userId == null) return;
        jdbcTemplate.update("DELETE FROM simulation_result WHERE task_id IN (SELECT id FROM experiment_task WHERE creator_id=?)", userId);
        jdbcTemplate.update("DELETE FROM task_execution WHERE task_id IN (SELECT id FROM experiment_task WHERE creator_id=?)", userId);
        jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_id IN (SELECT id FROM experiment_task WHERE creator_id=?)", userId);
        jdbcTemplate.update("DELETE FROM experiment_task WHERE creator_id=?", userId);
        jdbcTemplate.update("DELETE FROM simulation_scenario WHERE owner_id=?", userId);
        jdbcTemplate.update("DELETE FROM app_user WHERE id=?", userId);
    }

    @Test
    void duplicateClaimAndCompleteProduceOneExecutionAndOneResult() throws Exception {
        long taskId = insertPendingTask();
        String claimBody = "{\"workerId\":\"python-test\"}";

        mockMvc.perform(post(path(taskId, "claim"))
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody))
                .andExpect(status().isUnauthorized());

        MvcResult first = mockMvc.perform(post(path(taskId, "claim"))
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("CLAIMED"))
                .andExpect(jsonPath("$.data.scene.deviceCount").value(3))
                .andExpect(jsonPath("$.data.scene.wptTransmitPowerWatt").value(4))
                .andExpect(jsonPath("$.data.evaluation.numEpisodes").value(10))
                .andReturn();
        long executionId = objectMapper.readTree(first.getResponse().getContentAsString()).path("data").path("executionId").asLong();

        mockMvc.perform(post(path(taskId, "claim"))
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON).content(claimBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("RESUMABLE"))
                .andExpect(jsonPath("$.data.executionId").value(executionId));

        String complete = """
                {"executionId":%d,"modelId":"grpo-rsma-throughput-v1",
                 "checkpointSha256":"%s","baseSeed":2026,
                 "throughputMean":422.0089,"throughputStd":0.67,
                 "throughputMin":421.3,"throughputMax":422.7,
                 "totalTimesteps":1000,"totalEvaluationTimeSeconds":2.5,
                 "artifactPath":"python-worker/artifacts/task-test"}
                """.formatted(executionId, "a".repeat(64));
        mockMvc.perform(post(path(taskId, "complete"))
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON).content(complete))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("COMPLETED"));
        mockMvc.perform(post(path(taskId, "complete"))
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON).content(complete))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("ALREADY_HANDLED"));

        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE task_id=?", Integer.class, taskId));
        org.junit.jupiter.api.Assertions.assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM simulation_result WHERE task_id=?", Integer.class, taskId));
        org.junit.jupiter.api.Assertions.assertEquals("SUCCEEDED", jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id=?", String.class, taskId));
        org.junit.jupiter.api.Assertions.assertNull(jdbcTemplate.queryForObject(
                "SELECT average_aoi FROM simulation_result WHERE task_id=?", java.math.BigDecimal.class, taskId));
    }

    @Test
    void incompatibleDeviceCountIsRejectedAndTaskIsClosedAsFailed() throws Exception {
        long taskId = insertPendingTask(5);
        mockMvc.perform(post(path(taskId, "claim"))
                        .header("X-Worker-Token", "test-worker-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"workerId\":\"python-test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.outcome").value("REJECTED"));
        org.junit.jupiter.api.Assertions.assertEquals("FAILED", jdbcTemplate.queryForObject(
                "SELECT status FROM experiment_task WHERE id=?", String.class, taskId));
        org.junit.jupiter.api.Assertions.assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM task_execution WHERE task_id=?", Integer.class, taskId));
    }

    private long insertPendingTask() {
        return insertPendingTask(3);
    }

    private long insertPendingTask(int deviceCount) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        jdbcTemplate.update("INSERT INTO app_user(username,password_hash,role,status) VALUES(?,?, 'USER','ACTIVE')",
                "pyworker_" + suffix, "test-hash");
        userId = jdbcTemplate.queryForObject("SELECT id FROM app_user WHERE username=?", Long.class, "pyworker_" + suffix);
        String config = """
                {"deviceCount":%d,"antennaCount":1,"timeSlotCount":100,
                 "dataArrivalRate":3,"averageGreenEnergy":6,"batteryCapacity":12,
                 "dataBufferCapacity":3,"wptTransmitPower":4,
                 "deviceMaxTransmitPower":100,"accessScheme":"RSMA","randomSeed":2026}
                """.formatted(deviceCount);
        jdbcTemplate.update("INSERT INTO simulation_scenario(owner_id,name,objective,config_json) VALUES(?,?,'THROUGHPUT',CAST(? AS JSON))",
                userId, "python-worker-test", config);
        long scenarioId = jdbcTemplate.queryForObject(
                "SELECT id FROM simulation_scenario WHERE owner_id=? ORDER BY id DESC LIMIT 1", Long.class, userId);
        String snapshot = "{\"scenarioName\":\"python-worker-test\",\"description\":null,"
                + "\"objective\":\"THROUGHPUT\",\"config\":" + config + ",\"version\":0}";
        String training = "{\"maxTrainingSteps\":1000,\"learningRate\":0.0003,"
                + "\"batchSize\":64,\"discountFactor\":0.99,\"randomSeed\":2026}";
        jdbcTemplate.update("INSERT INTO experiment_task(task_no,scenario_id,scenario_snapshot_json,creator_id,algorithm,training_config_json,priority,status,idempotency_key,request_hash,submitted_at) "
                        + "VALUES(?,?,CAST(? AS JSON),?,'GRPO',CAST(? AS JSON),3,'PENDING',?,?,CURRENT_TIMESTAMP(3))",
                "T" + suffix, scenarioId, snapshot, userId, training, "idem-" + suffix, "b".repeat(64));
        return jdbcTemplate.queryForObject("SELECT id FROM experiment_task WHERE creator_id=? ORDER BY id DESC LIMIT 1", Long.class, userId);
    }

    private String path(long taskId, String action) {
        return "/api/v1/internal/worker/tasks/" + taskId + "/attempts/1/" + action;
    }
}
