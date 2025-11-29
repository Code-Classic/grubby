package com.codeclassic.grubby.domain.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CodeAnalysisSummary {
    /** Short list of endpoints detected via annotations in controllers */
    private List<EndpointDescriptor> endpoints;
    /** Top relevant files selected using featureContext scoring */
    private List<RelevantFile> relevantFiles;
    /** Optional notes about analysis limits/decisions */
    private List<String> notes;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelevantFile {
        private String path;         // repo-relative path
        private int score;           // relevance score
        private String firstLines;   // small snippet (e.g., top of file / class header)
    }
}
