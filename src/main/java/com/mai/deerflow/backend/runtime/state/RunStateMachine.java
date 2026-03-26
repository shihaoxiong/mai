package com.mai.deerflow.backend.runtime.state;

import com.mai.deerflow.backend.runtime.contract.RunStatus;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

@Component
/**
 * 平台侧 run 状态流转规则。
 *
 * 通过集中管理合法迁移，避免状态变更逻辑散落在 service/controller 中。
 */
public class RunStateMachine {

    private final Map<RunStatus, EnumSet<RunStatus>> transitions = new EnumMap<>(RunStatus.class);

    public RunStateMachine() {
        transitions.put(RunStatus.IDLE, EnumSet.of(RunStatus.RUNNING, RunStatus.WAITING_APPROVAL));
        transitions.put(RunStatus.RUNNING, EnumSet.of(
                RunStatus.COMPLETED,
                RunStatus.FAILED,
                RunStatus.WAITING_APPROVAL,
                RunStatus.WAITING_CLARIFICATION
        ));
        transitions.put(RunStatus.WAITING_APPROVAL, EnumSet.of(RunStatus.RUNNING, RunStatus.FAILED));
        transitions.put(RunStatus.WAITING_CLARIFICATION, EnumSet.of(RunStatus.RUNNING, RunStatus.FAILED));
        transitions.put(RunStatus.COMPLETED, EnumSet.of(RunStatus.RUNNING, RunStatus.WAITING_APPROVAL));
        transitions.put(RunStatus.FAILED, EnumSet.of(RunStatus.RUNNING, RunStatus.WAITING_APPROVAL));
    }

    /**
     * 校验并执行一次状态迁移。
     */
    public RunStatus transition(RunStatus current, RunStatus target) {
        if (current == target) {
            return target;
        }

        EnumSet<RunStatus> allowedTargets = transitions.getOrDefault(current, EnumSet.noneOf(RunStatus.class));
        if (!allowedTargets.contains(target)) {
            throw new InvalidRunStateTransitionException(current, target);
        }

        return target;
    }
}
