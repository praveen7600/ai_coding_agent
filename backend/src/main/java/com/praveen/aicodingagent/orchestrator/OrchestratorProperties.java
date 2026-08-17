package com.praveen.aicodingagent.orchestrator;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * maxIterations bounds AgentOrchestrator's tool-calling loop - see that
 * class's javadoc for why a bound exists at all (generateContent resends
 * the full history every call, so this caps both cost and worst-case
 * wall-clock time per task).
 *
 * Pulled out of a hardcoded constant because 8 - a reasonable ceiling for
 * "explore a bit, make one edit, verify it" - is too low for anything with
 * more moving parts: explore, edit file A, edit file B, run the build, fix
 * a compile error, run tests, fix a failing test is already 7 iterations
 * before the model has produced a final answer. That was the direct cause
 * of large tasks failing with "Agent did not produce a final answer within
 * 8 iterations" while small tasks (which fit inside that budget) succeeded.
 */
@ConfigurationProperties(prefix = "orchestrator")
public record OrchestratorProperties(
        int maxIterations
) {
    /**
     * 20 gives roughly 2-3x the room of the old hardcoded 8: enough for a
     * multi-file change plus a build-fix-test cycle, without going so high
     * that a genuinely confused loop burns an unbounded amount of Gemini
     * quota before the ceiling catches it. Non-positive/unset falls back
     * here rather than to 0, so an application.yml without this key
     * doesn't accidentally make every task fail immediately.
     */
    private static final int DEFAULT_MAX_ITERATIONS = 20;

    public int effectiveMaxIterations() {
        return maxIterations > 0 ? maxIterations : DEFAULT_MAX_ITERATIONS;
    }
}
