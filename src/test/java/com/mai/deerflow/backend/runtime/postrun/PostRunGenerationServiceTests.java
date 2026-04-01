package com.mai.deerflow.backend.runtime.postrun;

import com.mai.deerflow.backend.runtime.contract.ArtifactRef;
import com.mai.deerflow.backend.runtime.contract.TodoItem;
import com.mai.deerflow.backend.runtime.contract.TodoStatus;
import com.mai.deerflow.backend.runtime.contract.UploadRef;
import com.mai.deerflow.backend.runtime.subtask.SubTaskRecord;
import com.mai.deerflow.backend.runtime.subtask.SubTaskStatus;
import com.mai.deerflow.backend.runtime.subtask.SubTaskStep;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class PostRunGenerationServiceTests {

    private final PostRunGenerationService postRunGenerationService = new PostRunGenerationService();

    @Test
    void shouldGenerateAnalysisSuggestions() {
        PostRunGenerationResult result = postRunGenerationService.generate(
                "analyze the uploaded brief",
                "Processed: analyze the uploaded brief",
                List.of(),
                List.of(),
                List.of(),
                List.of()
        );

        assertThat(result.title()).isEqualTo("analyze the uploaded brief");
        assertThat(result.suggestions())
                .containsExactly(
                        "ask for risks and next steps",
                        "request a concise action checklist",
                        "continue this thread"
                );
    }

    @Test
    void shouldPrioritizeTodosArtifactsAndSubtasks() {
        PostRunGenerationResult result = postRunGenerationService.generate(
                "implement the backend flow",
                "parent_observation=ready",
                List.of(new TodoItem("todo-1", "finish runtime integration", TodoStatus.IN_PROGRESS)),
                List.of(new UploadRef("brief.md", "/uploads/brief.md", "/uploads/brief.md")),
                List.of(new ArtifactRef("summary.md", "/outputs/summary.md", "text/markdown")),
                List.of(new SubTaskRecord(
                        "task-1",
                        "thread-1",
                        "run-1",
                        "delegate work",
                        "check delegated work",
                        "SINGLE",
                        List.of(new SubTaskStep("worker", "review findings")),
                        30_000L,
                        0,
                        SubTaskStatus.COMPLETED,
                        "done",
                        null,
                        "2026-03-27T00:00:00Z",
                        "2026-03-27T00:00:01Z"
                ))
        );

        assertThat(result.suggestions())
                .containsExactly(
                        "continue with \"finish runtime integration\"",
                        "review the generated artifact \"summary.md\"",
                        "review the delegated subtask results"
                );
    }
}
