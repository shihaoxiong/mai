package com.mai.deerflow.backend.runtime.sandbox;

/**
 * sandbox 命令执行结果。
 */
public record CommandExecutionResult(
        int exitCode,
        String stdout,
        String stderr,
        boolean timedOut
) {
}
