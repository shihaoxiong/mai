package com.mai.deerflow.backend.runtime.contract;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RuntimeContractTests {

    @Test
    void threadStateSnapshotShouldExposeFrozenP0Fields() {
        ThreadStateSnapshot snapshot = new ThreadStateSnapshot(
                "thread-1",
                "run-1",
                RunStatus.RUNNING,
                new WorkspaceState("/workspace", "/uploads", "/outputs"),
                List.of(new UploadRef("brief.pdf", "/uploads/brief.pdf", "/uploads/brief.md")),
                List.of(new ArtifactRef("summary.md", "/outputs/summary.md", "text/markdown")),
                List.of(new TodoItem("todo-1", "Read uploaded brief", TodoStatus.IN_PROGRESS)),
                new ApprovalState("approval-1", ApprovalStatus.WAITING, "Need approval before execution"),
                List.of("continue analysis"),
                "Long-running analysis"
        );

        assertThat(snapshot.threadId()).isEqualTo("thread-1");
        assertThat(snapshot.runStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(snapshot.workspace().workspacePath()).isEqualTo("/workspace");
        assertThat(snapshot.uploads()).hasSize(1);
        assertThat(snapshot.artifacts()).hasSize(1);
        assertThat(snapshot.todos()).hasSize(1);
        assertThat(snapshot.approval().status()).isEqualTo(ApprovalStatus.WAITING);
    }

    @Test
    void runEventTypeShouldExposeStableWireNames() {
        assertThat(RunEventType.RUN_STARTED.wireName()).isEqualTo("run.started");
        assertThat(RunEventType.TOOL_CALL_STARTED.wireName()).isEqualTo("tool.call.started");
        assertThat(RunEventType.RUN_COMPLETED.wireName()).isEqualTo("run.completed");
    }
}
