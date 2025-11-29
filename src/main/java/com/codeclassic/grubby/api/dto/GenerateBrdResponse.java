package com.codeclassic.grubby.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class GenerateBrdResponse {
    private String requestId;
    private String status;
    private String message;
}