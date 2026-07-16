package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.api.ScenarioSnapshot;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;

public record SimulationExecutionContext(
        ExperimentTask task,
        ScenarioSnapshot scenarioSnapshot,
        TrainingConfigRequest trainingConfig
) {
}
