package com.chenmingqiang.wirelesssim.scenario.api;

import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;
import java.time.LocalDateTime;

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
