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
 *
 * Output truncation, deliberately here rather than in DockerContainerRuntime:
 * this is the seam where "sandbox output" becomes "conversation history",
 * and generateContent resends the FULL history on every call (see
 * AgentOrchestrator's javadoc). A large task's build/test output that goes
 * untruncated here gets resent, in full, on every subsequent iteration of
 * that same task - the token cost compounds with iteration count in a way
 * a small task's short-lived commands never trigger. Truncating at the
 * point output enters history (not at the Docker layer) keeps the SSE log
 * stream and any raw-output debugging unaffected; only what the model sees
 * next turn is capped.
 */
@Component
@RequiredArgsConstructor
public class ToolExecutor {

    /**
     * Kept well under Gemini's actual context window - this isn't a
     * "fits in the model" limit, it's a "don't let one chatty command eat
     * the whole per-task token budget" limit. Applied per stream (stdout
     * and stderr each get their own budget) so a command that fails with a
     * short, useful stderr but a huge stdout doesn't lose the stderr to
     * truncation math.
     */
    private static final int MAX_OUTPUT_CHARS = 8_000;

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
                        "stdout", truncate(result.stdout()),
                        "stderr", truncate(result.stderr())
                )
        );
    }

    /**
     * Head + tail rather than a plain head cutoff: for a failing build or
     * test run, the actionable error is almost always at the END of the
     * output (the final stack trace / assertion failure), while the
     * beginning is often just setup noise (dependency resolution, compiler
     * banners). Keeping both ends and cutting the (usually less useful)
     * middle gives the model the best shot at both "what command produced
     * this" context and "what actually failed" in a fixed budget.
     */
    private String truncate(String output) {
        if (output == null || output.length() <= MAX_OUTPUT_CHARS) {
            return output;
        }
        int half = MAX_OUTPUT_CHARS / 2;
        String head = output.substring(0, half);
        String tail = output.substring(output.length() - half);
        int omitted = output.length() - MAX_OUTPUT_CHARS;
        return head + "\n[... " + omitted + " characters truncated ...]\n" + tail;
    }

    private ConversationTurn.FunctionResult errorResult(String toolName, String message) {
        return new ConversationTurn.FunctionResult(toolName, Map.of("error", message));
    }
}
