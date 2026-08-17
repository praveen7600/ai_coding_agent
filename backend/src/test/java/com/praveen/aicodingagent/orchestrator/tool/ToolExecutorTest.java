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

    @Test
    void shortOutputIsPassedThroughUnchanged() {
        UUID taskId = UUID.randomUUID();
        when(sandboxManager.executeStreaming(eq(taskId), any()))
                .thenReturn(new ExecResult(0, "short output", "short error"));

        ConversationTurn.FunctionResult result = toolExecutor.execute(
                taskId,
                new ConversationTurn.ModelFunctionCall(ToolCatalog.RUN_COMMAND, Map.of("command", "echo hi"))
        );

        // Below the truncation threshold - must come back byte-for-byte
        // identical, not merely "close enough".
        assertThat(result.result()).containsEntry("stdout", "short output");
        assertThat(result.result()).containsEntry("stderr", "short error");
    }

    @Test
    void largeOutputIsTruncatedKeepingHeadAndTail() {
        // Large tasks are the ones that actually produce output this big -
        // a verbose build/test log - so this is the direct regression test
        // for "large tasks fail because their own tool output balloons the
        // context sent back to Gemini every subsequent iteration".
        UUID taskId = UUID.randomUUID();
        String head = "BUILD STARTED\n";
        String middle = "x".repeat(50_000);
        String tail = "\nFAILED: AssertionError at OrderServiceTest.java:42";
        String hugeStdout = head + middle + tail;

        when(sandboxManager.executeStreaming(eq(taskId), any()))
                .thenReturn(new ExecResult(1, hugeStdout, ""));

        ConversationTurn.FunctionResult result = toolExecutor.execute(
                taskId,
                new ConversationTurn.ModelFunctionCall(ToolCatalog.RUN_COMMAND, Map.of("command", "mvn test"))
        );

        String truncated = (String) result.result().get("stdout");
        // Must be meaningfully smaller than the original - proves
        // truncation actually happened, not just that the string changed.
        assertThat(truncated.length()).isLessThan(hugeStdout.length() / 2);
        // The two things a model needs to diagnose a failing build: what
        // command produced this, and what the actual failure was. Losing
        // either to truncation would defeat the point.
        assertThat(truncated).startsWith(head);
        assertThat(truncated).endsWith(tail);
        assertThat(truncated).contains("truncated");
    }

    @Test
    void truncationAppliesIndependentlyToStdoutAndStderr() {
        // A command can fail with a short, useful stderr but a huge,
        // mostly-irrelevant stdout (e.g. a verbose build tool). Each
        // stream needs its own budget so the short stderr never gets
        // squeezed out by stdout eating a shared one.
        UUID taskId = UUID.randomUUID();
        String hugeStdout = "y".repeat(50_000);
        String shortStderr = "compilation failed: missing semicolon on line 12";

        when(sandboxManager.executeStreaming(eq(taskId), any()))
                .thenReturn(new ExecResult(1, hugeStdout, shortStderr));

        ConversationTurn.FunctionResult result = toolExecutor.execute(
                taskId,
                new ConversationTurn.ModelFunctionCall(ToolCatalog.RUN_COMMAND, Map.of("command", "javac Main.java"))
        );

        assertThat(result.result()).containsEntry("stderr", shortStderr);
        assertThat(((String) result.result().get("stdout")).length()).isLessThan(hugeStdout.length());
    }
}
