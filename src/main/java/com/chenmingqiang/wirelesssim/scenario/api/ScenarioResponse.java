package com.chenmingqiang.wirelesssim.scenario.api;

import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;
import java.time.LocalDateTime;

/**
 * 教学注释：本文件为 api/ScenarioResponse.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record ScenarioResponse(
        Long id,
        String name,
        String description,
        ScenarioObjective objective,
        ScenarioConfigRequest config,
        Integer version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {
}
