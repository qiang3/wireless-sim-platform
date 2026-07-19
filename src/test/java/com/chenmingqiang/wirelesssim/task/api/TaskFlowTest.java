package com.chenmingqiang.wirelesssim.task.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chenmingqiang.wirelesssim.scenario.api.CreateScenarioRequest;
import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;
import com.chenmingqiang.wirelesssim.task.domain.TaskAlgorithm;
import com.chenmingqiang.wirelesssim.user.api.LoginRequest;
import com.chenmingqiang.wirelesssim.user.api.RegisterRequest;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@AutoConfigureMockMvc
class TaskFlowTest {

    private static final String PASSWORD = "SecurePass123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private final List<String> usernames = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        for (String username : usernames) {
            List<Long> userIds = jdbcTemplate.queryForList(
                    "SELECT id FROM app_user WHERE username = ?",
                    Long.class,
                    username
            );
            for (Long userId : userIds) {
                jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_type='EXPERIMENT_TASK' "
                        + "AND aggregate_id IN (SELECT id FROM experiment_task WHERE creator_id = ?)", userId);
                jdbcTemplate.update("DELETE FROM simulation_result WHERE task_id IN "
                        + "(SELECT id FROM experiment_task WHERE creator_id = ?)", userId);
                jdbcTemplate.update("DELETE FROM task_execution WHERE task_id IN "
                        + "(SELECT id FROM experiment_task WHERE creator_id = ?)", userId);
                jdbcTemplate.update("DELETE FROM experiment_task WHERE creator_id = ?", userId);
                jdbcTemplate.update("DELETE FROM simulation_scenario WHERE owner_id = ?", userId);
            }
            jdbcTemplate.update("DELETE FROM app_user WHERE username = ?", username);
        }
    }

    @Test
    void ownerCanSubmitListGetAndCancelTaskWithScenarioSnapshot() throws Exception {
        String token = registerAndLogin(newUsername());
        long scenarioId = createScenario(token);

        MvcResult submitResult = submitTask(token, "task-flow-1", taskRequest(scenarioId, TaskAlgorithm.GRPO))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andExpect(jsonPath("$.data.scenarioSnapshot.scenarioName").value("任务测试场景"))
                .andExpect(jsonPath("$.data.scenarioSnapshot.config.deviceCount").value(3))
                .andExpect(jsonPath("$.data.trainingConfig.batchSize").value(64))
                .andReturn();
        long taskId = responseDataId(submitResult);

        assertThatOutboxEvent(taskId, 1, 3);

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", bearer(token))
                        .param("status", "PENDING")
                        .param("algorithm", "GRPO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(taskId));

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarioId").value(scenarioId));

        mockMvc.perform(post("/api/v1/tasks/{id}/cancel", taskId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskActionRequest(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CANCELLED"))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(post("/api/v1/tasks/{id}/cancel", taskId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskActionRequest(0))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_VERSION_CONFLICT"));
    }

    @Test
    void idempotentReplayReturnsOriginalTaskAndRejectsDifferentRequest() throws Exception {
        String token = registerAndLogin(newUsername());
        long scenarioId = createScenario(token);
        String idempotencyKey = "same-business-operation";

        MvcResult first = submitTask(token, idempotencyKey, taskRequest(scenarioId, TaskAlgorithm.GRPO))
                .andExpect(status().isAccepted())
                .andReturn();
        long firstTaskId = responseDataId(first);

        submitTask(token, idempotencyKey, taskRequest(scenarioId, TaskAlgorithm.GRPO))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.id").value(firstTaskId));

        // 幂等重放只返回原任务，不产生第二条执行请求事件。
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?",
                Integer.class,
                firstTaskId
        )).isEqualTo(1);

        mockMvc.perform(get("/api/v1/tasks")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1));

        submitTask(token, idempotencyKey, taskRequest(scenarioId, TaskAlgorithm.PPO))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        mockMvc.perform(post("/api/v1/tasks")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(taskRequest(scenarioId, TaskAlgorithm.GRPO))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void taskEndpointsEnforceOwnershipAuthenticationAndValidation() throws Exception {
        String ownerToken = registerAndLogin(newUsername());
        String otherToken = registerAndLogin(newUsername());
        long scenarioId = createScenario(ownerToken);

        MvcResult result = submitTask(ownerToken, "owner-only", taskRequest(scenarioId, TaskAlgorithm.GRPO))
                .andExpect(status().isAccepted())
                .andReturn();
        long taskId = responseDataId(result);

        mockMvc.perform(get("/api/v1/tasks/{id}", taskId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("TASK_NOT_FOUND"));

        mockMvc.perform(post("/api/v1/tasks/{id}/cancel", taskId)
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskActionRequest(0))))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/api/v1/tasks"))
                .andExpect(status().isUnauthorized());

        CreateTaskRequest invalid = new CreateTaskRequest(
                scenarioId,
                TaskAlgorithm.GRPO,
                new TrainingConfigRequest(
                        1000,
                        BigDecimal.ZERO,
                        64,
                        new BigDecimal("0.99"),
                        1L
                ),
                5
        );
        submitTask(ownerToken, "invalid-config", invalid)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void failedTaskCanRetryWithinLimitButNotBeyondIt() throws Exception {
        String token = registerAndLogin(newUsername());
        long scenarioId = createScenario(token);
        MvcResult result = submitTask(token, "retry-task", taskRequest(scenarioId, TaskAlgorithm.GRPO))
                .andExpect(status().isAccepted())
                .andReturn();
        long taskId = responseDataId(result);

        jdbcTemplate.update(
                "UPDATE experiment_task SET status='FAILED', error_message='worker failed' WHERE id=?",
                taskId
        );

        mockMvc.perform(post("/api/v1/tasks/{id}/retry", taskId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskActionRequest(0))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.retryCount").value(1))
                .andExpect(jsonPath("$.data.errorMessage").doesNotExist())
                .andExpect(jsonPath("$.data.version").value(1));

        assertThatOutboxEvent(taskId, 2, 3);
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM outbox_event WHERE aggregate_id = ?",
                Integer.class,
                taskId
        )).isEqualTo(2);

        jdbcTemplate.update(
                "UPDATE experiment_task SET status='FAILED', retry_count=max_retry_count, "
                        + "lock_version=lock_version+1 WHERE id=?",
                taskId
        );

        mockMvc.perform(post("/api/v1/tasks/{id}/retry", taskId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new TaskActionRequest(2))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_RETRY_LIMIT_EXCEEDED"));
    }

    private org.springframework.test.web.servlet.ResultActions submitTask(
            String token,
            String idempotencyKey,
            CreateTaskRequest request
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/tasks")
                .header("Authorization", bearer(token))
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)));
    }

    private long createScenario(String token) throws Exception {
        CreateScenarioRequest request = new CreateScenarioRequest(
                "任务测试场景",
                "用于验证任务参数快照",
                ScenarioObjective.THROUGHPUT,
                new ScenarioConfigRequest(
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
                        20260716L
                )
        );
        MvcResult result = mockMvc.perform(post("/api/v1/scenarios")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andReturn();
        return responseDataId(result);
    }

    private CreateTaskRequest taskRequest(long scenarioId, TaskAlgorithm algorithm) {
        return new CreateTaskRequest(
                scenarioId,
                algorithm,
                new TrainingConfigRequest(
                        100000,
                        new BigDecimal("0.0003"),
                        64,
                        new BigDecimal("0.99"),
                        20260716L
                ),
                5
        );
    }

    private String registerAndLogin(String username) throws Exception {
        usernames.add(username);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, PASSWORD))))
                .andExpect(status().isCreated());

        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .at("/data/accessToken")
                .asText();
    }

    private long responseDataId(MvcResult result) throws Exception {
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return body.at("/data/id").asLong();
    }

    private String newUsername() {
        return "task_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    /** 验证指定任务尝试已经产生一条格式正确的待发布事件。 */
    private void assertThatOutboxEvent(long taskId, int attemptNo, int expectedPriority) {
        org.assertj.core.api.Assertions.assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM outbox_event
                WHERE aggregate_type = 'EXPERIMENT_TASK'
                  AND aggregate_id = ?
                  AND event_type = 'TASK_EXECUTION_REQUESTED'
                  AND attempt_no = ?
                  AND status = 'PENDING'
                  AND priority = ?
                """, Integer.class, taskId, attemptNo, expectedPriority)).isEqualTo(1);
    }
}
