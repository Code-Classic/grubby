package com.codeclassic.grubby.api.controller;

import com.codeclassic.grubby.api.dto.AuthResponse;
import com.codeclassic.grubby.api.dto.LoginRequest;
import com.codeclassic.grubby.api.dto.RegisterRequest;
import com.codeclassic.grubby.api.dto.UserProfileResponse;
import com.codeclassic.grubby.api.dto.UpdateProfileRequest;
import com.codeclassic.grubby.domain.entity.AppUser;
import com.codeclassic.grubby.repository.AppUserRepository;
import com.codeclassic.grubby.service.security.RefreshTokenService;
import com.codeclassic.grubby.util.JwtUtils;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Arrays;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
@Tag(name = "Auth", description = "Registration, login, and session management")
public class AuthController {

    private static final String REFRESH_COOKIE = "grubby_refresh";

    private final AppUserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final RefreshTokenService refreshTokenService;

    @Operation(summary = "Register a new user account")
    @PostMapping("/register")
    public ResponseEntity<AuthResponse> register(
            @Valid @RequestBody RegisterRequest req, HttpServletResponse response) {
        String email = req.getEmail().trim().toLowerCase();
        if (userRepository.existsByUsername(email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "An account with this email already exists");
        }
        AppUser user = AppUser.builder()
                .username(email)
                .firstName(req.getFirstName().trim())
                .lastName(req.getLastName().trim())
                .phone(req.getPhone() != null ? req.getPhone().trim() : null)
                .passwordHash(passwordEncoder.encode(req.getPassword()))
                .roles("ROLE_USER")
                .enabled(true)
                .build();
        userRepository.save(user);
        log.info("Registered new user '{}'", email);

        String token = jwtUtils.generate(email, user.getRoles());
        String refreshRaw = refreshTokenService.issue(email);
        setRefreshCookie(response, refreshRaw);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new AuthResponse(token, email, user.getFirstName(), user.getLastName(), user.getRoles()));
    }

    @Operation(summary = "Login and receive a JWT")
    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(
            @Valid @RequestBody LoginRequest req, HttpServletResponse response) {
        String email = req.getEmail().trim().toLowerCase();
        Authentication auth = authenticationManager.authenticate(
                new UsernamePasswordAuthenticationToken(email, req.getPassword()));

        AppUser user = userRepository.findByUsername(email)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid credentials"));

        String roles = auth.getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .collect(Collectors.joining(","));
        String token = jwtUtils.generate(email, roles);
        String refreshRaw = refreshTokenService.issue(email);
        setRefreshCookie(response, refreshRaw);

        log.info("User '{}' logged in", email);
        return ResponseEntity.ok(
                new AuthResponse(token, email, user.getFirstName(), user.getLastName(), roles));
    }

    @Operation(summary = "Rotate the refresh token and issue a new access token")
    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(HttpServletRequest request, HttpServletResponse response) {
        String raw = readRefreshCookie(request);
        if (raw == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "No refresh token");
        }
        RefreshTokenService.RotationResult rotated = refreshTokenService.rotate(raw);
        AppUser user = userRepository.findByUsername(rotated.userId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));

        String token = jwtUtils.generate(user.getUsername(), user.getRoles());
        setRefreshCookie(response, rotated.newRawToken());

        return ResponseEntity.ok(
                new AuthResponse(token, user.getUsername(), user.getFirstName(), user.getLastName(), user.getRoles()));
    }

    @Operation(summary = "Logout and revoke the refresh token")
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(HttpServletRequest request, HttpServletResponse response) {
        String raw = readRefreshCookie(request);
        if (raw != null) {
            refreshTokenService.revokeByRaw(raw);
        }
        clearRefreshCookie(response);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get the currently authenticated user's profile")
    @GetMapping("/me")
    public ResponseEntity<UserProfileResponse> me(@AuthenticationPrincipal UserDetails principal) {
        AppUser user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        return ResponseEntity.ok(new UserProfileResponse(
                user.getUsername(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getGithubLogin(),
                user.getGithubAccessToken() != null,
                user.getSlackWebhookUrl()
        ));
    }

    @Operation(summary = "Update user profile settings")
    @PutMapping("/me")
    public ResponseEntity<UserProfileResponse> updateProfile(
            @Valid @RequestBody UpdateProfileRequest req,
            @AuthenticationPrincipal UserDetails principal) {
        AppUser user = userRepository.findByUsername(principal.getUsername())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        if (req.getFirstName() != null) user.setFirstName(req.getFirstName().trim());
        if (req.getLastName() != null) user.setLastName(req.getLastName().trim());
        if (req.getPhone() != null) user.setPhone(req.getPhone().trim());
        if (req.getSlackWebhookUrl() != null) user.setSlackWebhookUrl(
                req.getSlackWebhookUrl().isBlank() ? null : req.getSlackWebhookUrl().trim());
        userRepository.save(user);
        return ResponseEntity.ok(new UserProfileResponse(
                user.getUsername(), user.getFirstName(), user.getLastName(),
                user.getPhone(), user.getGithubLogin(),
                user.getGithubAccessToken() != null, user.getSlackWebhookUrl()));
    }

    // ── Cookie helpers ────────────────────────────────────────────────────────

    private void setRefreshCookie(HttpServletResponse response, String rawToken) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, rawToken);
        cookie.setHttpOnly(true);
        cookie.setSecure(false); // set to true in production behind HTTPS
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge(30 * 24 * 60 * 60); // 30 days
        cookie.setAttribute("SameSite", "Strict");
        response.addCookie(cookie);
    }

    private void clearRefreshCookie(HttpServletResponse response) {
        Cookie cookie = new Cookie(REFRESH_COOKIE, "");
        cookie.setHttpOnly(true);
        cookie.setPath("/api/v1/auth");
        cookie.setMaxAge(0);
        response.addCookie(cookie);
    }

    private String readRefreshCookie(HttpServletRequest request) {
        if (request.getCookies() == null) return null;
        return Arrays.stream(request.getCookies())
                .filter(c -> REFRESH_COOKIE.equals(c.getName()))
                .map(Cookie::getValue)
                .findFirst()
                .orElse(null);
    }
}
