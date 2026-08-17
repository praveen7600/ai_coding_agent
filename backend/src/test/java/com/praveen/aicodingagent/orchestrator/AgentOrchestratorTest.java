package com.praveen.aicodingagent.orchestrator;

import com.praveen.aicodingagent.orchestrator.llm.ConversationTurn;
import com.praveen.aicodingagent.orchestrator.llm.ModelTurn;
import com.praveen.aicodingagent.orchestrator.tool.ToolCatalog;
import com.praveen.aicodingagent.orchestrator.tool.ToolExecutor;
import com.praveen.aicodingagent.sandbox.SandboxManager;
import com.praveen.aicodingagent.sandbox.runtime.ExecResult;
import com.praveen.aicodingagent.task.Task;
import com.praveen.aicodingagent.task.TaskRepository;
import com.praveen.aicodingagent.task.TaskService;
import com.praveen.aicodingagent.task.TaskStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Exercises AgentOrchestrator.run() against a FakeLlmClient scripted with a
 * fixed turn sequence, same pattern as FakeContainerRuntime in Milestone 2:
 * proves loop termination and state transitions without a real Gemini API
 * call or a real container. TaskService and ToolExecutor are real objects
 * here, not mocks - only their own dependencies (TaskRepository,
 * ApplicationEventPublisher, SandboxManager) are mocked, so the actual
 * ALLOWED_TRANSITIONS state machine and tool-dispatch logic run for real.
 *
 * run() is @Async in production, but calling it directly here executes it
 * synchronously on the test thread - Spring's proxying that makes @Async
 * actually hand off to a thread pool only kicks in when the bean is
 * resolved through the Spring context, which this unit test deliberately
 * doesn't stand up.
 */
@ExtendWith(MockitoExtension.class)
class AgentOrchestratorTest {

    @Mock
    private TaskRepository taskRepository;

    @Mock
    private ApplicationEventPublisher taskEventPublisher;

    @Mock
    private SandboxManager sandboxManager;

    @Mock
    private ApplicationEventPublisher orchestratorEventPublisher;

    private TaskService taskService;
    private ToolExecutor toolExecutor;
    private UUID taskId;
    private UUID userId;
    private Task task;

    @BeforeEach
    void setUp() {
        taskService = new TaskService(taskRepository, taskEventPublisher);
        toolExecutor = new ToolExecutor(sandboxManager);

        userId = UUID.randomUUID();
        taskId = UUID.randomUUID();
        task = Task.builder()
                .id(taskId)
                .userId(userId)
                .title("Add pagination to /orders")
                .description("Use limit/offset query params")
                .repoUrl("https://github.com/praveen7600/grocery-shop")
                .status(TaskStatus.RUNNING) // controller already did PENDING -> RUNNING before calling run()
                .createdAt(Instant.now())
                .updatedAt(Instant.now())
                .build();

        when(taskRepository.findById(taskId)).thenReturn(Optional.of(task));
    }

    @Test
    void modelTextAnswerCompletesTaskOnFirstIteration() {
        FakeLlmClient llmClient = new FakeLlmClient(new ConversationTurn.ModelText("Done, pagination added."));
        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient, new ToolCatalog(), toolExecutor, taskService, taskRepository, orchestratorEventPublisher,
                new OrchestratorProperties(0));

        orchestrator.run(taskId);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getResultSummary()).isEqualTo("Done, pagination added.");
        assertThat(llmClient.callCount()).isEqualTo(1);
    }

    @Test
    void functionCallThenTextRunsToolAndTransitionsThroughToolCalling() {
        ModelTurn functionCall = new ConversationTurn.ModelFunctionCall(
                ToolCatalog.RUN_COMMAND, Map.of("command", "cat pom.xml"));
        ModelTurn finalAnswer = new ConversationTurn.ModelText("pom.xml looks fine, no changes needed.");
        FakeLlmClient llmClient = new FakeLlmClient(functionCall, finalAnswer);

        when(sandboxManager.executeStreaming(eq(taskId), any()))
                .thenReturn(new ExecResult(0, "<project>...</project>", ""));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient, new ToolCatalog(), toolExecutor, taskService, taskRepository, orchestratorEventPublisher,
                new OrchestratorProperties(0));

        orchestrator.run(taskId);

        // Loop called generate() twice: once to get the function call, once
        // more after feeding the tool result back in to get the final answer.
        assertThat(llmClient.callCount()).isEqualTo(2);

        // Second call's history must contain the FunctionResult from the
        // first tool call - this is the actual "feeds it back to the model"
        // contract under test, not just that the loop ran twice.
        List<ConversationTurn> secondCallHistory = llmClient.historyAt(1);
        assertThat(secondCallHistory).anyMatch(turn -> turn instanceof ConversationTurn.FunctionResult);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        assertThat(task.getResultSummary()).isEqualTo("pom.xml looks fine, no changes needed.");

        verify(orchestratorEventPublisher).publishEvent(
                new ToolCallEvent(taskId, ToolCatalog.RUN_COMMAND, Map.of("command", "cat pom.xml")));
        verify(sandboxManager).executeStreaming(taskId, List.of("sh", "-c", "cat pom.xml"));
    }

    @Test
    void exhaustingIterationsWithoutAFinalAnswerFailsTheTask() {
        // Explicit small cap (rather than the real default of 20) so this
        // test stays fast and its own assertions - "8 function calls",
        // "fails after 8" - describe a cap this test controls, not a
        // production default that might change independently of it.
        int maxIterations = 8;
        ModelTurn[] neverEndingCalls = new ModelTurn[maxIterations];
        for (int i = 0; i < neverEndingCalls.length; i++) {
            neverEndingCalls[i] = new ConversationTurn.ModelFunctionCall(
                    ToolCatalog.RUN_COMMAND, Map.of("command", "echo still working " + i));
        }
        FakeLlmClient llmClient = new FakeLlmClient(neverEndingCalls);

        when(sandboxManager.executeStreaming(eq(taskId), any()))
                .thenReturn(new ExecResult(0, "still working", ""));

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient, new ToolCatalog(), toolExecutor, taskService, taskRepository, orchestratorEventPublisher,
                new OrchestratorProperties(maxIterations));

        orchestrator.run(taskId);

        assertThat(llmClient.callCount()).isEqualTo(maxIterations);
        assertThat(task.getStatus()).isEqualTo(TaskStatus.FAILED);
        assertThat(task.getResultSummary()).contains(maxIterations + " iterations");
    }

    @Test
    void unknownToolNameDoesNotCrashTheLoopAndModelGetsAChanceToRecover() {
        ModelTurn badCall = new ConversationTurn.ModelFunctionCall("delete_everything", Map.of());
        ModelTurn recovery = new ConversationTurn.ModelText("Apologies, I made a mistake earlier. Done now.");
        FakeLlmClient llmClient = new FakeLlmClient(badCall, recovery);

        AgentOrchestrator orchestrator = new AgentOrchestrator(
                llmClient, new ToolCatalog(), toolExecutor, taskService, taskRepository, orchestratorEventPublisher,
                new OrchestratorProperties(0));

        orchestrator.run(taskId);

        assertThat(task.getStatus()).isEqualTo(TaskStatus.COMPLETED);
        // ToolExecutor turns the unknown tool name into an error observation
        // rather than throwing - SandboxManager should never have been touched.
        assertThat(llmClient.historyAt(1)).anyMatch(turn ->
                turn instanceof ConversationTurn.FunctionResult fr && fr.result().containsKey("error"));
        verifyNoInteractions(sandboxManager);
    }
}
