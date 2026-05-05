package com.codeclassic.grubby.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AuthResponse {
    private String token;
    private String username;   // the email used as auth principal
    private String firstName;
    private String lastName;
    private String roles;
}
