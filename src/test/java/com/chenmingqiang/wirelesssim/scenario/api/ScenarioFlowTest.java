package com.chenmingqiang.wirelesssim.scenario.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;
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
class ScenarioFlowTest {

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
                jdbcTemplate.update("DELETE FROM simulation_scenario WHERE owner_id = ?", userId);
            }
            jdbcTemplate.update("DELETE FROM app_user WHERE username = ?", username);
        }
    }

    @Test
    void ownerCanCreateListUpdateAndArchiveScenario() throws Exception {
        String token = registerAndLogin(newUsername());

        MvcResult createResult = mockMvc.perform(post("/api/v1/scenarios")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest(3))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("吞吐量基准场景"))
                .andExpect(jsonPath("$.data.objective").value("THROUGHPUT"))
                .andExpect(jsonPath("$.data.config.deviceCount").value(3))
                .andExpect(jsonPath("$.data.config.accessScheme").value("RSMA"))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();

        JsonNode createdBody = objectMapper.readTree(createResult.getResponse().getContentAsByteArray());
        long scenarioId = createdBody.at("/data/id").asLong();

        mockMvc.perform(get("/api/v1/scenarios")
                        .header("Authorization", bearer(token))
                        .param("page", "0")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(scenarioId));

        mockMvc.perform(get("/api/v1/scenarios/{id}", scenarioId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.config.averageGreenEnergy").value(5.0));

        UpdateScenarioRequest updateRequest = new UpdateScenarioRequest(
                0,
                "吞吐量扩展场景",
                "设备数量增加后的对比实验",
                ScenarioObjective.THROUGHPUT,
                config(5)
        );

        mockMvc.perform(put("/api/v1/scenarios/{id}", scenarioId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("吞吐量扩展场景"))
                .andExpect(jsonPath("$.data.config.deviceCount").value(5))
                .andExpect(jsonPath("$.data.version").value(1));

        mockMvc.perform(put("/api/v1/scenarios/{id}", scenarioId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SCENARIO_VERSION_CONFLICT"));

        mockMvc.perform(delete("/api/v1/scenarios/{id}", scenarioId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNoContent());

        mockMvc.perform(get("/api/v1/scenarios/{id}", scenarioId)
                        .header("Authorization", bearer(token)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SCENARIO_NOT_FOUND"));

        mockMvc.perform(get("/api/v1/scenarios")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void userCannotReadUpdateOrArchiveAnotherUsersScenario() throws Exception {
        String ownerToken = registerAndLogin(newUsername());
        String otherToken = registerAndLogin(newUsername());

        MvcResult createResult = mockMvc.perform(post("/api/v1/scenarios")
                        .header("Authorization", bearer(ownerToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest(3))))
                .andExpect(status().isCreated())
                .andReturn();
        long scenarioId = objectMapper.readTree(createResult.getResponse().getContentAsByteArray())
                .at("/data/id")
                .asLong();

        mockMvc.perform(get("/api/v1/scenarios/{id}", scenarioId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());

        UpdateScenarioRequest updateRequest = new UpdateScenarioRequest(
                0,
                "越权修改",
                null,
                ScenarioObjective.THROUGHPUT,
                config(4)
        );
        mockMvc.perform(put("/api/v1/scenarios/{id}", scenarioId)
                        .header("Authorization", bearer(otherToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(updateRequest)))
                .andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/scenarios/{id}", scenarioId)
                        .header("Authorization", bearer(otherToken)))
                .andExpect(status().isNotFound());
    }

    @Test
    void scenarioEndpointsRequireAuthenticationAndValidateNestedConfig() throws Exception {
        mockMvc.perform(get("/api/v1/scenarios"))
                .andExpect(status().isUnauthorized());

        String token = registerAndLogin(newUsername());
        CreateScenarioRequest invalidRequest = new CreateScenarioRequest(
                "非法场景",
                null,
                ScenarioObjective.THROUGHPUT,
                config(0)
        );

        mockMvc.perform(post("/api/v1/scenarios")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(invalidRequest)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/api/v1/scenarios")
                        .header("Authorization", bearer(token))
                        .param("page", "-1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    private String registerAndLogin(String username) throws Exception {
        usernames.add(username);
        mockMvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new RegisterRequest(username, PASSWORD))))
                .andExpect(status().isCreated());

        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new LoginRequest(username, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(loginResult.getResponse().getContentAsByteArray())
                .at("/data/accessToken")
                .asText();
    }

    private CreateScenarioRequest createRequest(int deviceCount) {
        return new CreateScenarioRequest(
                "吞吐量基准场景",
                "用于GRPO与PPO对比",
                ScenarioObjective.THROUGHPUT,
                config(deviceCount)
        );
    }

    private ScenarioConfigRequest config(int deviceCount) {
        return new ScenarioConfigRequest(
                deviceCount,
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
        );
    }

    private String newUsername() {
        return "scenario_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
