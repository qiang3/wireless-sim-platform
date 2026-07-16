package com.chenmingqiang.wirelesssim.scenario.application;

import com.chenmingqiang.wirelesssim.common.api.PageResponse;
import com.chenmingqiang.wirelesssim.common.error.BusinessException;
import com.chenmingqiang.wirelesssim.scenario.api.CreateScenarioRequest;
import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.api.ScenarioResponse;
import com.chenmingqiang.wirelesssim.scenario.api.UpdateScenarioRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.SimulationScenario;
import com.chenmingqiang.wirelesssim.scenario.infrastructure.ScenarioMapper;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class ScenarioService {

    private final ScenarioMapper scenarioMapper;
    private final ObjectMapper objectMapper;

    public ScenarioService(ScenarioMapper scenarioMapper, ObjectMapper objectMapper) {
        this.scenarioMapper = scenarioMapper;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public ScenarioResponse create(Long ownerId, CreateScenarioRequest request) {
        SimulationScenario scenario = new SimulationScenario();
        scenario.setOwnerId(ownerId);
        scenario.setName(request.name().trim());
        scenario.setDescription(normalizeDescription(request.description()));
        scenario.setObjective(request.objective());
        scenario.setConfigJson(writeConfig(request.config()));

        scenarioMapper.insert(scenario);
        return get(ownerId, scenario.getId());
    }

    @Transactional(readOnly = true)
    public ScenarioResponse get(Long ownerId, Long scenarioId) {
        return toResponse(requireOwnedActive(ownerId, scenarioId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ScenarioResponse> list(Long ownerId, int page, int size) {
        long total = scenarioMapper.countActiveByOwnerId(ownerId);
        int offset = Math.multiplyExact(page, size);
        List<ScenarioResponse> content = scenarioMapper.findActivePageByOwnerId(ownerId, offset, size)
                .stream()
                .map(this::toResponse)
                .toList();
        return PageResponse.of(content, page, size, total);
    }

    @Transactional
    public ScenarioResponse update(Long ownerId, Long scenarioId, UpdateScenarioRequest request) {
        requireOwnedActive(ownerId, scenarioId);

        SimulationScenario scenario = new SimulationScenario();
        scenario.setId(scenarioId);
        scenario.setOwnerId(ownerId);
        scenario.setName(request.name().trim());
        scenario.setDescription(normalizeDescription(request.description()));
        scenario.setObjective(request.objective());
        scenario.setConfigJson(writeConfig(request.config()));
        scenario.setVersion(request.version());

        if (scenarioMapper.updateOwnedWithVersion(scenario) == 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SCENARIO_VERSION_CONFLICT",
                    "场景已被其他请求修改，请刷新后重试"
            );
        }
        return get(ownerId, scenarioId);
    }

    @Transactional
    public void archive(Long ownerId, Long scenarioId) {
        requireOwnedActive(ownerId, scenarioId);
        if (scenarioMapper.countTasksByScenarioId(scenarioId) > 0) {
            throw new BusinessException(
                    HttpStatus.CONFLICT,
                    "SCENARIO_IN_USE",
                    "场景已被实验任务使用，不能归档"
            );
        }
        if (scenarioMapper.archiveOwned(scenarioId, ownerId) == 0) {
            throw scenarioNotFound();
        }
    }

    private SimulationScenario requireOwnedActive(Long ownerId, Long scenarioId) {
        SimulationScenario scenario = scenarioMapper.findActiveOwnedById(scenarioId, ownerId);
        if (scenario == null) {
            throw scenarioNotFound();
        }
        return scenario;
    }

    private ScenarioResponse toResponse(SimulationScenario scenario) {
        return new ScenarioResponse(
                scenario.getId(),
                scenario.getName(),
                scenario.getDescription(),
                scenario.getObjective(),
                readConfig(scenario.getConfigJson()),
                scenario.getVersion(),
                scenario.getCreatedAt(),
                scenario.getUpdatedAt()
        );
    }

    private String writeConfig(ScenarioConfigRequest config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JacksonException exception) {
            throw new IllegalStateException("场景配置序列化失败", exception);
        }
    }

    private ScenarioConfigRequest readConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, ScenarioConfigRequest.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的场景配置无法解析", exception);
        }
    }

    private String normalizeDescription(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }

    private BusinessException scenarioNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "SCENARIO_NOT_FOUND", "场景不存在");
    }
}
