package com.chenmingqiang.wirelesssim.task.api;

import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;

public record ScenarioSnapshot(
        String scenarioName,
        String description,
        ScenarioObjective objective,
        ScenarioConfigRequest config,
        Integer version
) {
}
