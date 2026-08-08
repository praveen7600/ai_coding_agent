package com.praveen.aicodingagent.task.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(

        @NotBlank
        @Size(max = 200)
        String title,

        @Size(max = 4000)
        String description,

        @NotBlank
        @Pattern(
                regexp = "^https://github\\.com/[\\w.-]+/[\\w.-]+(\\.git)?$",
                message = "repoUrl must be a valid GitHub HTTPS URL"
        )
        String repoUrl
) {
}
