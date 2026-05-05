package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateProfileRequest {

    @Size(max = 100)
    private String firstName;

    @Size(max = 100)
    private String lastName;

    @Pattern(regexp = "^[+]?[\\d\\s\\-().]{7,25}$|^$")
    @Size(max = 25)
    private String phone;

    @Size(max = 500)
    private String slackWebhookUrl;
}
