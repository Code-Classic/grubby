package com.codeclassic.grubby.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BrdPreviewResponse {
    private String requestId;
    private String format; // markdown|json
    private String content;
}