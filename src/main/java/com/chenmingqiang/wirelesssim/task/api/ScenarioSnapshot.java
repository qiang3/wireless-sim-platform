package com.chenmingqiang.wirelesssim.task.api;

import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;

/**
 * 教学注释：本文件为 api/ScenarioSnapshot.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record ScenarioSnapshot(
        String scenarioName,
        String description,
        ScenarioObjective objective,
        ScenarioConfigRequest config,
        Integer version
) {
}
