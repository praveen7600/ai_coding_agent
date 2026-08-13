package com.praveen.aicodingagent.orchestrator;

import com.praveen.aicodingagent.orchestrator.llm.ConversationTurn;
import com.praveen.aicodingagent.orchestrator.llm.LlmClient;
import com.praveen.aicodingagent.orchestrator.llm.LlmClientException;
import com.praveen.aicodingagent.orchestrator.llm.ModelTurn;
import com.praveen.aicodingagent.orchestrator.tool.ToolCatalog;
import com.praveen.aicodingagent.orchestrator.tool.ToolExecutor;
import com.praveen.aicodingagent.sandbox.SandboxException;
import com.praveen.aicodingagent.task.InvalidTaskStateTransitionException;
import com.praveen.aicodingagent.task.Task;
import com.praveen.aicodingagent.task.TaskNotFoundException;
import com.praveen.aicodingagent.task.TaskRepository;
import com.praveen.aicodingagent.task.TaskService;
import com.praveen.aicodingagent.task.TaskStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The tool-calling loop itself: ask the model what to do next, either
 * finish with a text answer or run one tool and feed the observation back,
 * repeat. See the architecture note in the project status doc for why the
 * shape is this simple - generateContent is stateless so the whole history
 * rides along on every call, and there's no separate "planning" phase
 * distinct from "decide next action" here, deliberately, until a real need
 * for one shows up.
 *
 * Runs on its own thread pool (see AsyncConfig) rather than the request
 * thread that calls run() - a task can take minutes across several LLM
 * round trips and sandbox execs, and the caller (the /execute trigger
 * endpoint) isn't meant to block on that; progress is meant to be watched
 * over the SSE stream a client already knows how to subscribe to
 * (Milestone 4), not the HTTP response of the trigger call.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AgentOrchestrator {

    /**
     * Picked deliberately before building this, not discovered mid-runaway-
     * loop: generateContent resends the full history every call, so this
     * bounds both API cost and worst-case wall-clock time per task. 8 gives
     * the model room for a few exploratory commands and at least one retry
     * after a failed one, without a confused loop running indefinitely
     * against someone's Gemini quota.
     */
    private static final int MAX_ITERATIONS = 8;

    private final LlmClient llmClient;
    private final ToolCatalog toolCatalog;
    private final ToolExecutor toolExecutor;
    private final TaskService taskService;
    private final TaskRepository taskRepository;
    private final ApplicationEventPublisher eventPublisher;

    @Async("agentTaskExecutor")
    public void run(UUID taskId) {
        Task task = taskRepository.findById(taskId)
                .orElseThrow(() -> new TaskNotFoundException(taskId));
        UUID userId = task.getUserId();

        List<ConversationTurn> history = new ArrayList<>();
        history.add(new ConversationTurn.UserMessage(buildPrompt(task)));

        try {
            for (int iteration = 1; iteration <= MAX_ITERATIONS; iteration++) {
                ModelTurn turn = llmClient.generate(history, toolCatalog.availableTools());
                // Safe: ModelTurn is implemented only by ConversationTurn.ModelText
                // and ConversationTurn.ModelFunctionCall (see ModelTurn's javadoc),
                // both of which are themselves ConversationTurn - this is an
                // upcast, not a real type check.
                history.add((ConversationTurn) turn);

                if (turn instanceof ConversationTurn.ModelText modelText) {
                    taskService.completeWithResult(taskId, userId, modelText.text());
                    return;
                }

                ConversationTurn.ModelFunctionCall call = (ConversationTurn.ModelFunctionCall) turn;

                taskService.transitionStatus(taskId, userId, TaskStatus.TOOL_CALLING);
                eventPublisher.publishEvent(new ToolCallEvent(taskId, call.name(), call.args()));

                ConversationTurn.FunctionResult result = toolExecutor.execute(taskId, call);
                history.add(result);

                taskService.transitionStatus(taskId, userId, TaskStatus.RUNNING);
            }

            log.warn("Task {} exhausted {} iterations without a final text answer", taskId, MAX_ITERATIONS);
            taskService.failWithReason(taskId, userId,
                    "Agent did not produce a final answer within " + MAX_ITERATIONS + " iterations");
        } catch (InvalidTaskStateTransitionException e) {
            // The task reached a terminal state from outside this loop -
            // most likely a user-initiated cancel racing an in-flight
            // iteration. Not a failure of the loop itself: the state
            // machine correctly rejected the transition, so just stop.
            log.info("Task {} loop stopped, task already in a terminal state: {}", taskId, e.getMessage());
        } catch (LlmClientException | SandboxException e) {
            log.error("Task {} failed: {}", taskId, e.getMessage(), e);
            safeFail(taskId, userId, "Agent failed: " + e.getMessage());
        } catch (RuntimeException e) {
            log.error("Task {} failed unexpectedly", taskId, e);
            safeFail(taskId, userId, "Agent failed unexpectedly: " + e.getMessage());
        }
    }

    /**
     * failWithReason() is itself a transitionStatus call, so it can throw
     * InvalidTaskStateTransitionException too (e.g. the task was cancelled
     * in the moment between the exception above being caught and this
     * running). That's not a new failure worth logging as an error - it's
     * the same race as the InvalidTaskStateTransitionException catch above,
     * just arriving one call later.
     */
    private void safeFail(UUID taskId, UUID userId, String reason) {
        try {
            taskService.failWithReason(taskId, userId, reason);
        } catch (InvalidTaskStateTransitionException e) {
            log.info("Task {} could not transition to FAILED, already terminal: {}", taskId, e.getMessage());
        }
    }

    String buildPrompt(Task task) {
        StringBuilder prompt = new StringBuilder(task.getTitle());
        if (task.getDescription() != null && !task.getDescription().isBlank()) {
            prompt.append("\n\n").append(task.getDescription());
        }
        return prompt.toString();
    }
}
