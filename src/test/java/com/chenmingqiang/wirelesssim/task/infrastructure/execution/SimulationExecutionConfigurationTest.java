package com.chenmingqiang.wirelesssim.task.infrastructure.execution;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class SimulationExecutionConfigurationTest {

    @Test
    void createsBoundedExecutorFromProperties() {
        SimulationExecutionProperties properties = new SimulationExecutionProperties(
                true,
                2,
                2,
                20,
                1000,
                20,
                200,
                10000,
                30
        );

        ThreadPoolTaskExecutor executor = new SimulationExecutionConfiguration()
                .simulationTaskExecutor(properties);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaxPoolSize()).isEqualTo(2);
            assertThat(executor.getQueueCapacity()).isEqualTo(20);
            assertThat(executor.getThreadNamePrefix()).isEqualTo("simulation-worker-");
        } finally {
            executor.shutdown();
        }
    }
}
