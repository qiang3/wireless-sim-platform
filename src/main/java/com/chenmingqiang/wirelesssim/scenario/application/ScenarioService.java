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

/**
 * 场景应用服务：编排场景的创建、查询、分页、更新和归档，并保证用户数据隔离。
 */
// Spring说明：将该类注册为业务服务Bean，其他组件可通过构造方法注入它。
@Service
public class ScenarioService {

    /** 执行场景表相关 SQL 的 MyBatis 代理。 */
    private final ScenarioMapper scenarioMapper;
    /** 在类型安全的配置对象与数据库 JSON 字符串之间转换。 */
    private final ObjectMapper objectMapper;

    /** 方法说明：`ScenarioService`封装下面这段业务或转换逻辑。 */
    public ScenarioService(ScenarioMapper scenarioMapper, ObjectMapper objectMapper) {
        this.scenarioMapper = scenarioMapper;
        this.objectMapper = objectMapper;
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** 创建属于当前用户的场景；ownerId 从可信 JWT 提取，不接受前端自行指定。 */
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

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional(readOnly = true)
    /** 按场景 ID 和用户 ID 联合查询，避免用户越权读取他人的场景。 */
    public ScenarioResponse get(Long ownerId, Long scenarioId) {
        return toResponse(requireOwnedActive(ownerId, scenarioId));
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional(readOnly = true)
    /** 分页查询当前用户的有效场景，并同时返回总条数。 */
    public PageResponse<ScenarioResponse> list(Long ownerId, int page, int size) {
        long total = scenarioMapper.countActiveByOwnerId(ownerId);
        int offset = Math.multiplyExact(page, size); // SQL OFFSET = 页码 × 每页数量；溢出时直接报错。
        List<ScenarioResponse> content = scenarioMapper.findActivePageByOwnerId(ownerId, offset, size)
                .stream()
                .map(this::toResponse)
                .toList();
        return PageResponse.of(content, page, size, total);
    }

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** 使用客户端提交的 version 执行乐观锁更新，防止并发编辑互相覆盖。 */
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

    // 事务说明：方法由Spring事务代理执行；运行时异常会使本次数据库修改整体回滚。

    @Transactional
    /** 归档未被任务引用的场景；归档代替物理删除，便于保留历史数据。 */
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

    /** 获取当前用户拥有的有效场景，不存在或不属于当前用户都统一返回 404。 */
    private SimulationScenario requireOwnedActive(Long ownerId, Long scenarioId) {
        SimulationScenario scenario = scenarioMapper.findActiveOwnedById(scenarioId, ownerId);
        if (scenario == null) {
            throw scenarioNotFound();
        }
        return scenario;
    }

    /** 将数据库领域对象转换为 API 响应对象，避免直接暴露持久化结构。 */
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

    /** 把经过 Bean Validation 校验的配置对象序列化成 JSON 保存。 */
    private String writeConfig(ScenarioConfigRequest config) {
        try {
            return objectMapper.writeValueAsString(config);
        } catch (JacksonException exception) {
            throw new IllegalStateException("场景配置序列化失败", exception);
        }
    }

    /** 把数据库 JSON 还原成结构化配置，返回给调用方。 */
    private ScenarioConfigRequest readConfig(String configJson) {
        try {
            return objectMapper.readValue(configJson, ScenarioConfigRequest.class);
        } catch (JacksonException exception) {
            throw new IllegalStateException("数据库中的场景配置无法解析", exception);
        }
    }

    /** 去除描述首尾空白，并把空字符串统一转换为 null。 */
    private String normalizeDescription(String description) {
        return description == null || description.isBlank() ? null : description.trim();
    }

    /** 方法说明：`scenarioNotFound`封装下面这段业务或转换逻辑。 */
    private BusinessException scenarioNotFound() {
        return new BusinessException(HttpStatus.NOT_FOUND, "SCENARIO_NOT_FOUND", "场景不存在");
    }
}
