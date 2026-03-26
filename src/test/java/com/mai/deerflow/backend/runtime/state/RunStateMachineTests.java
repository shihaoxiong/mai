package com.mai.deerflow.backend.runtime.state;

import com.mai.deerflow.backend.runtime.contract.RunStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RunStateMachineTests {

    private final RunStateMachine runStateMachine = new RunStateMachine();

    @Test
    void shouldAllowExpectedTransitions() {
        assertThat(runStateMachine.transition(RunStatus.IDLE, RunStatus.RUNNING)).isEqualTo(RunStatus.RUNNING);
        assertThat(runStateMachine.transition(RunStatus.RUNNING, RunStatus.COMPLETED)).isEqualTo(RunStatus.COMPLETED);
        assertThat(runStateMachine.transition(RunStatus.COMPLETED, RunStatus.RUNNING)).isEqualTo(RunStatus.RUNNING);
        assertThat(runStateMachine.transition(RunStatus.FAILED, RunStatus.RUNNING)).isEqualTo(RunStatus.RUNNING);
    }

    @Test
    void shouldRejectInvalidTransitions() {
        assertThatThrownBy(() -> runStateMachine.transition(RunStatus.IDLE, RunStatus.COMPLETED))
                .isInstanceOf(InvalidRunStateTransitionException.class)
                .hasMessageContaining("IDLE -> COMPLETED");

        assertThatThrownBy(() -> runStateMachine.transition(RunStatus.COMPLETED, RunStatus.FAILED))
                .isInstanceOf(InvalidRunStateTransitionException.class)
                .hasMessageContaining("COMPLETED -> FAILED");
    }
}
