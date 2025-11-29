package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class GenerateBrdRequest {
    @NotBlank
    private String repoUrl;
    private String branch;
    private String commitSha;
    private String featureContext;
    private String projectType;
    private String authType; // none|token|oauth
    private String authToken; // optional; in production store a reference only
    private boolean forceReanalyze;
    private Map<String, String> options;
}