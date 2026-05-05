package com.codeclassic.grubby.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Response for GET /api/v1/brd/:id/analysis
 * Returns the endpoints, relevant files, and notes discovered during code analysis.
 */
@Data
@AllArgsConstructor
public class BrdAnalysisResponse {

    private String requestId;

    /** HTTP endpoints detected in the codebase via AST or heuristic analysis */
    private List<EndpointInfo> endpoints;

    /** Files scored most relevant to the featureContext */
    private List<RelevantFile> relevantFiles;

    /** Notes about analysis decisions or limitations */
    private List<String> notes;

    @Data
    @AllArgsConstructor
    public static class EndpointInfo {
        private String httpMethod;  // GET, POST, PUT, etc.
        private String path;        // /api/v1/...
        private String controller;  // controller class name
        private String method;      // handler method name
        private String summary;     // javadoc / heuristic summary
    }

    @Data
    @AllArgsConstructor
    public static class RelevantFile {
        private String path;
        private int score;
        private String firstLines;
    }
}
