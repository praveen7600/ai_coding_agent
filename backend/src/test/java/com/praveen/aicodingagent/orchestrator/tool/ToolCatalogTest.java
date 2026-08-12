package com.praveen.aicodingagent.orchestrator.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ToolCatalogTest {

    @Test
    void exposesRunCommandWithARequiredStringCommandParameter() {
        ToolCatalog catalog = new ToolCatalog();

        assertThat(catalog.availableTools()).hasSize(1);

        ToolDefinition runCommand = catalog.availableTools().get(0);
        assertThat(runCommand.name()).isEqualTo(ToolCatalog.RUN_COMMAND);
        assertThat(runCommand.description()).isNotBlank();

        @SuppressWarnings("unchecked")
        var properties = (java.util.Map<String, Object>) runCommand.parameters().get("properties");
        assertThat(properties).containsKey("command");
        assertThat(runCommand.parameters().get("required")).isEqualTo(java.util.List.of("command"));
    }
}
