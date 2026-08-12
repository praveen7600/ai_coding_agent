package com.praveen.aicodingagent.orchestrator.tool;

import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * The tools the agent is allowed to call. v1 ships with a single tool,
 * run_command, deliberately - not read_file/write_file as separate tools.
 *
 * Reasoning: a shell command already covers reading (cat), writing
 * (echo/heredoc), and everything else (mvn test, git diff, ls) through one
 * interface the model already understands well from training data. Adding
 * read_file/write_file as distinct tools would mean two more JSON schemas
 * to define, two more ToolExecutor branches to test, and two more ways for
 * the model to pick "the wrong one" for a task - for no capability gain
 * over run_command. If a specific tool (e.g. a structured diff/patch tool)
 * earns its keep later because free-form shell proves too unreliable for
 * it, add it then, deliberately, not speculatively now.
 */
@Component
public class ToolCatalog {

    public static final String RUN_COMMAND = "run_command";

    private final List<ToolDefinition> tools = List.of(
            new ToolDefinition(
                    RUN_COMMAND,
                    "Runs a shell command inside the task's sandbox container and returns its exit code, "
                            + "stdout, and stderr. Supports pipes, redirects, and && - e.g. "
                            + "'cat pom.xml', 'mvn test', \"echo 'content' > file.txt\", 'git diff HEAD~1'. "
                            + "The working directory is the task's workspace.",
                    Map.of(
                            "type", "OBJECT",
                            "properties", Map.of(
                                    "command", Map.of(
                                            "type", "STRING",
                                            "description", "The full shell command line to execute."
                                    )
                            ),
                            "required", List.of("command")
                    )
            )
    );

    public List<ToolDefinition> availableTools() {
        return tools;
    }
}
