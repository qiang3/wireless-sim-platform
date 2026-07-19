package com.chenmingqiang.wirelesssim.task.domain;

import com.chenmingqiang.wirelesssim.scenario.api.ScenarioConfigRequest;
import com.chenmingqiang.wirelesssim.scenario.domain.AccessScheme;
import com.chenmingqiang.wirelesssim.task.api.ScenarioSnapshot;
import com.chenmingqiang.wirelesssim.task.api.TrainingConfigRequest;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.SplittableRandom;
import org.springframework.stereotype.Component;

// Spring说明：将该类交给Spring容器创建和管理。

/**
 * Java 模拟结果生成器，用于验证异步执行和结果闭环。
 * 公式是确定性的工程合成公式，simulationMode=JAVA_MOCK、scientificResult=false，
 * 不代表 GRPO/PPO 等算法的真实科研性能，也不人为制造算法间的优劣。
 */
@Component
public class JavaMockSimulationEngine {

    /** 使用场景种子与训练种子生成可复现的吞吐量、AoI 和收敛步数。 */
    public JavaMockSimulationResult simulate(
            ScenarioSnapshot snapshot,
            TrainingConfigRequest trainingConfig
    ) {
        ScenarioConfigRequest config = snapshot.config();
        long deterministicSeed = mixSeeds(config.randomSeed(), trainingConfig.randomSeed());
        SplittableRandom random = new SplittableRandom(deterministicSeed); // 相同输入得到相同伪随机扰动。

        double antennaGain = Math.log1p(config.antennaCount());
        double harvestedEnergy = config.averageGreenEnergy().doubleValue()
                + config.wptTransmitPower().doubleValue() * 0.65;
        double devicePowerGain = Math.sqrt(config.deviceMaxTransmitPower().doubleValue());
        double load = config.deviceCount() * config.dataArrivalRate().doubleValue();
        double bufferRelief = Math.log1p(config.dataBufferCapacity().doubleValue());
        double slotGain = Math.log1p(config.timeSlotCount());
        double accessFactor = accessFactor(config.accessScheme());
        double deterministicJitter = 0.95 + random.nextDouble() * 0.10;

        double throughput = antennaGain
                * Math.log1p(harvestedEnergy)
                * devicePowerGain
                * bufferRelief
                * slotGain
                * accessFactor
                * deterministicJitter
                / (1.0 + load);
        throughput = Math.max(0.000001, throughput);

        double averageAoi = config.deviceCount()
                * (1.0 + config.dataArrivalRate().doubleValue())
                / throughput
                * (1.0 + 1.0 / slotGain);

        double convergenceRatio = 0.65 + random.nextDouble() * 0.20;
        int convergenceStep = Math.max(
                1,
                (int) Math.round(trainingConfig.maxTrainingSteps() * convergenceRatio)
        );

        return new JavaMockSimulationResult(
                decimal(throughput),
                decimal(averageAoi),
                convergenceStep,
                deterministicSeed,
                JavaMockSimulationResult.JAVA_MOCK_MODE,
                false
        );
    }

    /** 合并两个随机种子，使场景或训练种子任一变化都会改变模拟序列。 */
    private long mixSeeds(long scenarioSeed, long trainingSeed) {
        return scenarioSeed * 31L + trainingSeed;
    }

    /** 工程演示用接入方式系数，不应解读为科研对比结论。 */
    private double accessFactor(AccessScheme accessScheme) {
        return switch (accessScheme) {
            case RSMA -> 1.00;
            case NOMA -> 0.92;
            case FDMA -> 0.82;
        };
    }

    /** 方法说明：`decimal`封装下面这段业务或转换逻辑。 */
    private BigDecimal decimal(double value) {
        return BigDecimal.valueOf(value).setScale(8, RoundingMode.HALF_UP);
    }
}
