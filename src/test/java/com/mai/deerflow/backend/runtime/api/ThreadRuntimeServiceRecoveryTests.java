package com.mai.deerflow.backend.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.graph.RuntimeGraphFactory;
import com.mai.deerflow.backend.runtime.state.RunStateMachine;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class ThreadRuntimeServiceRecoveryTests {

    @Autowired
    private ThreadRuntimeService threadRuntimeService;

    @Autowired
    private ThreadWorkspaceService threadWorkspaceService;

    @Autowired
    private RuntimeGraphFactory runtimeGraphFactory;

    @Autowired
    private LeadAgentFactory leadAgentFactory;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private RunStateMachine runStateMachine;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UploadService uploadService;

    @Test
    void shouldRecoverThreadSnapshotFromWorkspaceMetadata() {
        threadRuntimeService.createThread("recovery-thread");
        ThreadStateSnapshot completedSnapshot = threadRuntimeService.runThread("recovery-thread", "recover this thread");

        ThreadRuntimeService recoveredService = new ThreadRuntimeService(
                threadWorkspaceService,
                runtimeGraphFactory,
                leadAgentFactory,
                chatModel,
                runStateMachine,
                objectMapper,
                uploadService
        );

        ThreadStateSnapshot recoveredSnapshot = recoveredService.getThread("recovery-thread");

        assertThat(recoveredSnapshot.threadId()).isEqualTo("recovery-thread");
        assertThat(recoveredSnapshot.runId()).isEqualTo(completedSnapshot.runId());
        assertThat(recoveredSnapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(recoveredSnapshot.title()).isEqualTo(completedSnapshot.title());
        assertThat(recoveredSnapshot.workspace().outputsPath()).isEqualTo(completedSnapshot.workspace().outputsPath());
    }
}
