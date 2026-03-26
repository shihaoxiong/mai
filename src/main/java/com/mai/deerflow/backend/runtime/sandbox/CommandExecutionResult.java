package com.mai.deerflow.backend.runtime.sandbox;

public record CommandExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut
) {
}
