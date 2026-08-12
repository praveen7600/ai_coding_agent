package com.praveen.aicodingagent.orchestrator.tool;

import com.praveen.aicodingagent.orchestrator.llm.ConversationTurn;
import com.praveen.aicodingagent.sandbox.SandboxManager;
import com.praveen.aicodingagent.sandbox.runtime.ExecResult;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The seam between "the model decided to call a tool" and "something
 * actually happened in the sandbox". This is the class most likely to grow
 * a second/third tool later - dispatch is a simple if/else on tool name
 * today because there's exactly one tool; if that grows past two or three,
 * a Map<String, ToolHandler> registered per tool is the natural refactor,
 * not a speculative abstraction to build now for one tool.
 *
 * Failure semantics, deliberately chosen: a command that runs but exits
 * non-zero is NOT a Java exception here - it's normal tool output the model
 * needs to see and react to (that's what makes retrying failed commands
 * look agentic rather than the task just dying). Malformed arguments from
 * the model (missing "command", wrong type) are also turned into an error
 * observation rather than thrown, so a single bad tool call doesn't fail
 * the whole task - the model gets a chance to self-correct next turn.
 * Genuine infrastructure failure (SandboxException - Docker unreachable,
 * container crashed) is allowed to propagate, because that's not something
 * the model calling the tool again can fix.
 */
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    private final SandboxManager sandboxManager;

    public ConversationTurn.FunctionResult execute(UUID taskId, ConversationTurn.ModelFunctionCall call) {
        if (!ToolCatalog.RUN_COMMAND.equals(call.name())) {
            return errorResult(call.name(), "Unknown tool: '" + call.name() + "'. Available tools: "
                    + ToolCatalog.RUN_COMMAND);
        }

        Object commandArg = call.args().get("command");
        if (!(commandArg instanceof String command) || command.isBlank()) {
            return errorResult(call.name(), "run_command requires a non-empty string 'command' argument, got: "
                    + commandArg);
        }

        // Wrapped through sh -c rather than exec'd as a raw arg list -
        // that's what lets the model use pipes, &&, and redirects the way
        // it naturally would in a shell command, instead of us trying to
        // tokenize its command string ourselves (fragile: breaks on quoted
        // args, globs, etc.).
        ExecResult result = sandboxManager.executeStreaming(taskId, List.of("sh", "-c", command));

        return new ConversationTurn.FunctionResult(
                call.name(),
                Map.of(
                        "exitCode", result.exitCode(),
                        "stdout", result.stdout(),
                        "stderr", result.stderr()
                )
        );
    }

    private ConversationTurn.FunctionResult errorResult(String toolName, String message) {
        return new ConversationTurn.FunctionResult(toolName, Map.of("error", message));
    }
}
