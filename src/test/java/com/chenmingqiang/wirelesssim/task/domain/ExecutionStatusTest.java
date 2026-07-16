package com.chenmingqiang.wirelesssim.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExecutionStatusTest {

    @Test
    void onlyCompletedExecutionStatesAreTerminal() {
        assertThat(ExecutionStatus.QUEUED.isTerminal()).isFalse();
        assertThat(ExecutionStatus.RUNNING.isTerminal()).isFalse();
        assertThat(ExecutionStatus.SUCCEEDED.isTerminal()).isTrue();
        assertThat(ExecutionStatus.FAILED.isTerminal()).isTrue();
        assertThat(ExecutionStatus.CANCELLED.isTerminal()).isTrue();
    }
}
