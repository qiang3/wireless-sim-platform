package com.chenmingqiang.wirelesssim.task.application;

import com.chenmingqiang.wirelesssim.task.api.ScenarioSnapshot;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import com.chenmingqiang.wirelesssim.task.domain.ExperimentTask;

/**
 * 教学注释：本文件为 application/SimulationExecutionContext.java。
 * 请结合类、字段、方法旁的中文说明理解它在分层架构中的职责。
 */
public record SimulationExecutionContext(
        ExperimentTask task,
        ScenarioSnapshot scenarioSnapshot,
        TrainingConfigRequest trainingConfig
) {
}
