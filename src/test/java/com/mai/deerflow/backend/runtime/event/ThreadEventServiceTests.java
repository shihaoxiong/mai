package com.mai.deerflow.backend.runtime.event;

import com.mai.deerflow.backend.runtime.contract.RunEventType;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.Map;

class ThreadEventServiceTests {

    private final ThreadEventService threadEventService = new ThreadEventService();

    @Test
    void shouldReplayApprovalRequiredEvent() {
        threadEventService.emit("thread-events", "run-1", RunEventType.APPROVAL_REQUIRED, Map.of("approvalId", "approval-1"));

        StepVerifier.create(threadEventService.stream("thread-events").take(1))
                .assertNext(event -> {
                    assert event.event().equals("approval.required");
                    assert event.data() != null;
                    assert event.data().runId().equals("run-1");
                })
                .verifyComplete();
    }
}
