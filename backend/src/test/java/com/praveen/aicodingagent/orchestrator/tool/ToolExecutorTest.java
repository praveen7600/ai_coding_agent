package com.praveen.aicodingagent.orchestrator.tool;

import com.praveen.aicodingagent.orchestrator.llm.ConversationTurn;
import com.praveen.aicodingagent.sandbox.SandboxManager;
import com.praveen.aicodingagent.sandbox.runtime.ExecResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    @Mock
    private SandboxManager sandboxManager;

    @InjectMocks
    private ToolExecutor toolExecutor;

    @Test
    void runCommandWrapsThroughShellAndReturnsExecResultAsObservation() {
        UUID taskId = UUID.randomUUID();
        when(sandboxManager.executeStreaming(eq(taskId), any()))
                .thenReturn(new ExecResult(0, "hello\n", ""));

        ConversationTurn.FunctionResult result = toolExecutor.execute(
                taskId,
                new ConversationTurn.ModelFunctionCall(ToolCatalog.RUN_COMMAND, Map.of("command", "echo hello"))
        );

        // sh -c wrapping is the actual contract under test - if this ever
        // changes to raw exec, every tool call using &&/pipes/redirects
        // silently breaks, so pin the exact command list passed through.
        verify(sandboxManager).executeStreaming(taskId, List.of("sh", "-c", "echo hello"));

        assertThat(result.name()).isEqualTo(ToolCatalog.RUN_COMMAND);
        assertThat(result.result())
                .containsEntry("exitCode", 0)
                .containsEntry("stdout", "hello\n")
                .containsEntry("stderr", "");
    }

    @Test
    void nonZeroExitIsReturnedAsObservationNotThrown() {
        UUID taskId = UUID.randomUUID();
        when(sandboxManager.executeStreaming(eq(taskId), any()))
                .thenReturn(new ExecResult(1, "", "file not found"));

        ConversationTurn.FunctionResult result = toolExecutor.execute(
                taskId,
                new ConversationTurn.ModelFunctionCall(ToolCatalog.RUN_COMMAND, Map.of("command", "cat missing.txt"))
        );

        // The point being tested: a failed command is a normal observation
        // the model gets to see and react to, not a Java exception that
        // kills the task.
        assertThat(result.result()).containsEntry("exitCode", 1);
        assertThat(result.result()).containsEntry("stderr", "file not found");
    }

    @Test
    void unknownToolNameReturnsErrorObservationWithoutTouchingSandbox() {
        UUID taskId = UUID.randomUUID();

        ConversationTurn.FunctionResult result = toolExecutor.execute(
                taskId,
                new ConversationTurn.ModelFunctionCall("delete_everything", Map.of())
        );

        assertThat(result.result()).containsKey("error");
        verifyNoInteractions(sandboxManager);
    }

    @Test
    void missingCommandArgReturnsErrorObservationWithoutTouchingSandbox() {
        UUID taskId = UUID.randomUUID();

        ConversationTurn.FunctionResult result = toolExecutor.execute(
                taskId,
                new ConversationTurn.ModelFunctionCall(ToolCatalog.RUN_COMMAND, Map.of())
        );

        assertThat(result.result()).containsKey("error");
        verifyNoInteractions(sandboxManager);
    }

    @Test
    void blankCommandArgReturnsErrorObservation() {
        UUID taskId = UUID.randomUUID();

        ConversationTurn.FunctionResult result = toolExecutor.execute(
                taskId,
                new ConversationTurn.ModelFunctionCall(ToolCatalog.RUN_COMMAND, Map.of("command", "   "))
        );

        assertThat(result.result()).containsKey("error");
        verifyNoInteractions(sandboxManager);
    }
}
