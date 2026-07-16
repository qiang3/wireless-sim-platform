package com.chenmingqiang.wirelesssim.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import com.chenmingqiang.wirelesssim.scenario.domain.ScenarioObjective;
import com.chenmingqiang.wirelesssim.task.api.ScenarioSnapshot;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class JavaMockSimulationEngineTest {

    private final JavaMockSimulationEngine engine = new JavaMockSimulationEngine();

    @Test
    void sameInputsProduceSameClearlyMarkedNonScientificResult() {
        ScenarioSnapshot snapshot = snapshot(42L);
        TrainingConfigRequest training = training(2025L);

        JavaMockSimulationResult first = engine.simulate(snapshot, training);
        JavaMockSimulationResult second = engine.simulate(snapshot, training);

        assertThat(first).isEqualTo(second);
        assertThat(first.simulationMode()).isEqualTo("JAVA_MOCK");
        assertThat(first.scientificResult()).isFalse();
        assertThat(first.throughput()).isPositive();
        assertThat(first.averageAoi()).isPositive();
        assertThat(first.convergenceStep()).isBetween(650, 850);
    }

    @Test
    void changingSeedChangesSyntheticResult() {
        assertThat(engine.simulate(snapshot(42L), training(2025L)))
                .isNotEqualTo(engine.simulate(snapshot(43L), training(2025L)));
    }

    private ScenarioSnapshot snapshot(long seed) {
        return new ScenarioSnapshot(
                "固定种子测试场景",
                "仅验证后端执行闭环",
                ScenarioObjective.THROUGHPUT,
                new ScenarioConfigRequest(
                        8,
                        4,
                        1000,
                        new BigDecimal("0.25"),
                        new BigDecimal("5.0"),
                        new BigDecimal("20.0"),
                        new BigDecimal("100.0"),
                        new BigDecimal("10.0"),
                        new BigDecimal("2.0"),
                        AccessScheme.RSMA,
                        seed
                ),
                0
        );
    }

    private TrainingConfigRequest training(long seed) {
        return new TrainingConfigRequest(
                1000,
                new BigDecimal("0.0003"),
                64,
                new BigDecimal("0.99"),
                seed
        );
    }
}
