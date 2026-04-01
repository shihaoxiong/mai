package com.mai.deerflow.backend.runtime.state;

import com.mai.deerflow.backend.runtime.contract.RunStatus;

public class InvalidRunStateTransitionException extends RuntimeException {

    public InvalidRunStateTransitionException(RunStatus current, RunStatus target) {
        super("Invalid run status transition: " + current + " -> " + target);
    }
}
