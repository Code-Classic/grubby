package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record BrdRefineRequest(
        @NotBlank @Size(max = 2000) String prompt
) {}
