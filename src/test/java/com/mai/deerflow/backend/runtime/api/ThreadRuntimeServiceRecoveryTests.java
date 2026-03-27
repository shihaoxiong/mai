package com.mai.deerflow.backend.runtime.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.mai.deerflow.backend.runtime.agent.LeadAgentFactory;
import com.mai.deerflow.backend.runtime.agent.RuntimeAgentEnhancementService;
import com.mai.deerflow.backend.runtime.artifact.ArtifactService;
import com.mai.deerflow.backend.runtime.checkpoint.RuntimeCheckpointService;
import com.mai.deerflow.backend.runtime.contract.ApprovalStatus;
import com.mai.deerflow.backend.runtime.contract.RunStatus;
import com.mai.deerflow.backend.runtime.event.ThreadEventService;
import com.mai.deerflow.backend.runtime.contract.ThreadStateSnapshot;
import com.mai.deerflow.backend.runtime.graph.RuntimeGraphFactory;
import com.mai.deerflow.backend.runtime.memory.MemoryExtractorJob;
import com.mai.deerflow.backend.runtime.postrun.PostRunGenerationService;
import com.mai.deerflow.backend.runtime.state.RunStateMachine;
import com.mai.deerflow.backend.runtime.subtask.SubTaskExecutor;
import com.mai.deerflow.backend.runtime.upload.UploadService;
import com.mai.deerflow.backend.runtime.workspace.ThreadWorkspaceService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private RuntimeAgentEnhancementService runtimeAgentEnhancementService;

    @Autowired
    @Qualifier("runtimeChatModel")
    private ChatModel chatModel;

    @Autowired
    private RunStateMachine runStateMachine;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UploadService uploadService;

    @Autowired
    private ArtifactService artifactService;

    @Autowired
    private ThreadEventService threadEventService;

    @Autowired
    private MemoryExtractorJob memoryExtractorJob;

    @Autowired
    private SubTaskExecutor subTaskExecutor;

    @Autowired
    private PostRunGenerationService postRunGenerationService;

    @Autowired
    private RuntimeCheckpointService runtimeCheckpointService;

    @Test
    void shouldRecoverThreadSnapshotFromRuntimeCheckpoint() {
        String threadId = "recovery-thread-" + UUID.randomUUID();
        threadRuntimeService.createThread(threadId);
        ThreadStateSnapshot completedSnapshot = threadRuntimeService.runThread(threadId, "recover this thread");

        Path snapshotFile = threadWorkspaceService.getWorkspace(threadId)
                .threadRoot()
                .resolve("metadata")
                .resolve("thread-state.json");
        assertThat(Files.exists(snapshotFile)).isFalse();

        ThreadRuntimeService recoveredService = new ThreadRuntimeService(
                threadWorkspaceService,
                runtimeGraphFactory,
                leadAgentFactory,
                runtimeAgentEnhancementService,
                chatModel,
                runStateMachine,
                objectMapper,
                uploadService,
                artifactService,
                threadEventService,
                memoryExtractorJob,
                subTaskExecutor,
                postRunGenerationService,
                runtimeCheckpointService
        );

        ThreadStateSnapshot recoveredSnapshot = recoveredService.getThread(threadId);

        assertThat(recoveredSnapshot.threadId()).isEqualTo(threadId);
        assertThat(recoveredSnapshot.runId()).isEqualTo(completedSnapshot.runId());
        assertThat(recoveredSnapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(recoveredSnapshot.title()).isEqualTo(completedSnapshot.title());
        assertThat(recoveredSnapshot.workspace().outputsPath()).isEqualTo(completedSnapshot.workspace().outputsPath());
    }

    @Test
    void shouldRecoverWaitingApprovalFromPendingApprovalMetadata() {
        String threadId = "approval-recovery-thread-" + UUID.randomUUID();
        threadRuntimeService.createThread(threadId);
        ThreadStateSnapshot waitingSnapshot = threadRuntimeService.runThread(
                threadId,
                "recover approval state",
                true,
                "Need human approval"
        );

        ThreadRuntimeService recoveredService = new ThreadRuntimeService(
                threadWorkspaceService,
                runtimeGraphFactory,
                leadAgentFactory,
                runtimeAgentEnhancementService,
                chatModel,
                runStateMachine,
                objectMapper,
                uploadService,
                artifactService,
                threadEventService,
                memoryExtractorJob,
                subTaskExecutor,
                postRunGenerationService,
                runtimeCheckpointService
        );

        ThreadStateSnapshot recoveredSnapshot = recoveredService.getThread(threadId);

        assertThat(recoveredSnapshot.threadId()).isEqualTo(threadId);
        assertThat(recoveredSnapshot.runId()).isEqualTo(waitingSnapshot.runId());
        assertThat(recoveredSnapshot.runStatus()).isEqualTo(RunStatus.WAITING_APPROVAL);
        assertThat(recoveredSnapshot.approval().status()).isEqualTo(ApprovalStatus.WAITING);
        assertThat(recoveredSnapshot.approval().reason()).isEqualTo("Need human approval");

        Path approvalFile = threadWorkspaceService.getWorkspace(threadId)
                .threadRoot()
                .resolve("metadata")
                .resolve("pending-approval.json");
        assertThat(Files.exists(approvalFile)).isFalse();
    }

    @Test
    void shouldResumeApprovedThreadAfterServiceRecreatedUsingCheckpointPendingApproval() {
        String threadId = "approval-resume-thread-" + UUID.randomUUID();
        threadRuntimeService.createThread(threadId);
        ThreadStateSnapshot waitingSnapshot = threadRuntimeService.runThread(
                threadId,
                "resume this after approval",
                true,
                "Need explicit approval"
        );

        threadRuntimeService.submitApproval(
                threadId,
                waitingSnapshot.approval().approvalId(),
                new ApprovalSubmissionRequest(ApprovalDecision.APPROVE, "approved by reviewer")
        );

        ThreadRuntimeService recoveredService = new ThreadRuntimeService(
                threadWorkspaceService,
                runtimeGraphFactory,
                leadAgentFactory,
                runtimeAgentEnhancementService,
                chatModel,
                runStateMachine,
                objectMapper,
                uploadService,
                artifactService,
                threadEventService,
                memoryExtractorJob,
                subTaskExecutor,
                postRunGenerationService,
                runtimeCheckpointService
        );

        ThreadStateSnapshot resumedSnapshot = recoveredService.resumeThread(
                threadId,
                new ResumeThreadRequest("continue now")
        );

        assertThat(resumedSnapshot.threadId()).isEqualTo(threadId);
        assertThat(resumedSnapshot.runId()).isEqualTo(waitingSnapshot.runId());
        assertThat(resumedSnapshot.runStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(resumedSnapshot.approval().status()).isEqualTo(ApprovalStatus.APPROVED);
    }

    @Test
    void shouldRecoverThreadContextFromRuntimeCheckpoint() {
        String threadId = "thread-context-recovery-" + UUID.randomUUID();
        String userId = "recovery-user-" + UUID.randomUUID();
        threadRuntimeService.createThread(threadId);
        threadRuntimeService.runThread(
                threadId,
                "bind this thread to a user",
                false,
                null,
                userId
        );

        Path threadContextFile = threadWorkspaceService.getWorkspace(threadId)
                .threadRoot()
                .resolve("metadata")
                .resolve("thread-context.json");
        assertThat(Files.exists(threadContextFile)).isFalse();

        ThreadRuntimeService recoveredService = new ThreadRuntimeService(
                threadWorkspaceService,
                runtimeGraphFactory,
                leadAgentFactory,
                runtimeAgentEnhancementService,
                chatModel,
                runStateMachine,
                objectMapper,
                uploadService,
                artifactService,
                threadEventService,
                memoryExtractorJob,
                subTaskExecutor,
                postRunGenerationService,
                runtimeCheckpointService
        );

        assertThatThrownBy(() -> recoveredService.runThread(
                threadId,
                "reuse with another user",
                false,
                null,
                "another-user"
        )).isInstanceOf(ThreadContextConflictException.class);
    }
}
