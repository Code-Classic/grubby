package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ConfluencePushRequest {

    @NotBlank
    private String confluenceUrl; // e.g. https://myorg.atlassian.net/wiki

    @NotBlank
    private String email; // Atlassian account email for Basic Auth

    @NotBlank
    private String apiToken; // Atlassian API token

    @NotBlank
    @Size(max = 50)
    private String spaceKey;

    private String parentPageId; // optional

    @Size(max = 255)
    private String pageTitle; // optional; defaults to BRD title
}
