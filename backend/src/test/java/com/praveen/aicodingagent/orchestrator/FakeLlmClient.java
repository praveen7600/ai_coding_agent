package com.praveen.aicodingagent.orchestrator;

import com.praveen.aicodingagent.orchestrator.llm.ConversationTurn;
import com.praveen.aicodingagent.orchestrator.llm.LlmClient;
import com.praveen.aicodingagent.orchestrator.llm.ModelTurn;
import com.praveen.aicodingagent.orchestrator.tool.ToolDefinition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * In-memory fake standing in for GeminiClient, same reasoning as
 * FakeContainerRuntime for SandboxManager's tests: AgentOrchestrator's loop
 * logic (does it stop on ModelText, does it feed a FunctionResult back in,
 * does it give up after the configured max iterations) gets tested against
 * a scripted sequence of real ModelTurn values instead of a mock returning
 * canned objects for a method that's called in a loop.
 */
public class FakeLlmClient implements LlmClient {

    private final Deque<ModelTurn> scriptedTurns;
    private final List<List<ConversationTurn>> historySnapshots = new ArrayList<>();

    public FakeLlmClient(ModelTurn... scriptedTurns) {
        this.scriptedTurns = new ArrayDeque<>(List.of(scriptedTurns));
    }

    @Override
    public ModelTurn generate(List<ConversationTurn> history, List<ToolDefinition> tools) {
        historySnapshots.add(List.copyOf(history));
        if (scriptedTurns.isEmpty()) {
            throw new IllegalStateException(
                    "FakeLlmClient ran out of scripted turns - the loop called generate() more times than the "
                            + "test expected. Either the test's script is too short, or the loop isn't stopping "
                            + "when it should.");
        }
        return scriptedTurns.poll();
    }

    /** Test hook: how many times generate() was actually called. */
    public int callCount() {
        return historySnapshots.size();
    }

    /** Test hook: the history as it was passed in on a given call, for asserting it grew correctly. */
    public List<ConversationTurn> historyAt(int callIndex) {
        return historySnapshots.get(callIndex);
    }
}
