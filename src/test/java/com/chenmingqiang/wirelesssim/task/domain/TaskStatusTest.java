package com.chenmingqiang.wirelesssim.task.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TaskStatusTest {

    @Test
    void pendingTaskCanBeQueuedOrCancelled() {
        assertThat(TaskStatus.PENDING.canTransitionTo(TaskStatus.QUEUED)).isTrue();
        assertThat(TaskStatus.PENDING.canTransitionTo(TaskStatus.CANCELLED)).isTrue();
        assertThat(TaskStatus.PENDING.canTransitionTo(TaskStatus.SUCCEEDED)).isFalse();
    }

    @Test
    void runningTaskCanFinishFailOrBeCancelled() {
        assertThat(TaskStatus.RUNNING.canTransitionTo(TaskStatus.SUCCEEDED)).isTrue();
        assertThat(TaskStatus.RUNNING.canTransitionTo(TaskStatus.FAILED)).isTrue();
        assertThat(TaskStatus.RUNNING.canTransitionTo(TaskStatus.CANCELLED)).isTrue();
    }

    @Test
    void queuedTaskCanStartFailOrBeCancelled() {
        assertThat(TaskStatus.QUEUED.canTransitionTo(TaskStatus.RUNNING)).isTrue();
        assertThat(TaskStatus.QUEUED.canTransitionTo(TaskStatus.FAILED)).isTrue();
        assertThat(TaskStatus.QUEUED.canTransitionTo(TaskStatus.CANCELLED)).isTrue();
    }

    @Test
    void terminalTaskCannotTransitionAgain() {
        assertThat(TaskStatus.SUCCEEDED.canTransitionTo(TaskStatus.RUNNING)).isFalse();
        assertThat(TaskStatus.CANCELLED.canTransitionTo(TaskStatus.QUEUED)).isFalse();
    }

    @Test
    void failedTaskCanBeRetried() {
        assertThat(TaskStatus.FAILED.canTransitionTo(TaskStatus.QUEUED)).isTrue();
    }
}
