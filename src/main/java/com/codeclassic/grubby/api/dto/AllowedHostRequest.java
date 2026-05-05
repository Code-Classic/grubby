package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/** P2 — payload for POST /api/v1/admin/allowed-hosts */
@Data
public class AllowedHostRequest {
    @NotBlank
    @Size(max = 253)
    private String hostname;

    @Size(max = 500)
    private String description;
}
