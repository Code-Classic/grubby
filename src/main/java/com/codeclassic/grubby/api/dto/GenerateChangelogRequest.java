package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GenerateChangelogRequest {

    @NotBlank
    @Size(max = 500)
    @Pattern(regexp = "^(https?|git|ssh)://.*", message = "Must be a valid repository URL")
    private String repoUrl;

    @Size(max = 200)
    private String branch;

    @Size(max = 500)
    private String authToken;

    /** Starting point: tag, branch, or commit SHA (exclusive — commits after this point). */
    @NotBlank
    @Size(max = 200)
    private String fromRef;

    /** Ending point: tag, branch, or commit SHA. Defaults to HEAD when blank. */
    @Size(max = 200)
    private String toRef;
}
