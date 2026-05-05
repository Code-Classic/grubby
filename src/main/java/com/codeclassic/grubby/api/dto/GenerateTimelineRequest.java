package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class GenerateTimelineRequest {

    @NotBlank
    @Size(max = 500)
    @Pattern(
        regexp = "^(https?|git|ssh)://.*",
        message = "repoUrl must start with https://, http://, git://, or ssh://"
    )
    private String repoUrl;

    @Size(max = 200)
    private String branch;

    /** Optional GitHub PAT for private repos. */
    @Size(max = 500)
    private String authToken;
}
