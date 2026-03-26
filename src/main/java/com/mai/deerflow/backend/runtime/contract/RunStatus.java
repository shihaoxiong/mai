package com.mai.deerflow.backend.runtime.contract;

public enum RunStatus {
    IDLE,
    RUNNING,
    WAITING_APPROVAL,
    WAITING_CLARIFICATION,
    FAILED,
    COMPLETED
}
