package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.NotBlank;

public record BrdSaveEditRequest(@NotBlank String content) {}
