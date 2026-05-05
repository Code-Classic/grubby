package com.codeclassic.grubby.api.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class RegisterRequest {

    @NotBlank
    @Size(max = 100)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    private String lastName;

    @NotBlank
    @Email(message = "Must be a valid email address")
    @Size(max = 200)
    private String email;

    @Size(min = 7, max = 25)
    @Pattern(regexp = "^[+]?[\\d\\s\\-().]*$", message = "Invalid phone number format")
    private String phone;

    @NotBlank
    @Size(min = 8, max = 128, message = "Password must be at least 8 characters")
    private String password;
}
