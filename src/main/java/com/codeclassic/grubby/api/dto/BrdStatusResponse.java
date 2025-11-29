package com.codeclassic.grubby.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class BrdStatusResponse {
    private String requestId;
    private String status;
    private Integer progressPct;
    private String stage;
    private String errorMessage;
}