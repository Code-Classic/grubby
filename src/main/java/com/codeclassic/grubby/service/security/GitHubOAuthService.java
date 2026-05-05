package com.codeclassic.grubby.service.security;

import com.codeclassic.grubby.domain.entity.AppUser;
import com.codeclassic.grubby.repository.AppUserRepository;
import com.codeclassic.grubby.util.AesEncryptionUtil;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class GitHubOAuthService {

    private final AppUserRepository userRepo;
    private final AesEncryptionUtil aes;

    @Value("${github.oauth.client-id:}")
    private String clientId;

    @Value("${github.oauth.client-secret:}")
    private String clientSecret;

    @Value("${github.oauth.redirect-uri:}")
    private String redirectUri;

    @Value("${github.oauth.frontend-url:http://localhost:5173}")
    private String frontendUrl;

    @Value("${jwt.secret}")
    private String jwtSecret;

    private final RestTemplate restTemplate = new RestTemplate();

    // ── Authorize ────────────────────────────────────────────────────────────

    /**
     * Builds the GitHub OAuth authorization URL with an HMAC-signed state parameter.
     * State = base64(userId:timestamp:hmac) — stateless CSRF protection.
     */
    public URI buildAuthorizationUrl(String userId) {
        String state = buildState(userId);
        return UriComponentsBuilder
                .fromUriString("https://github.com/login/oauth/authorize")
                .queryParam("client_id", clientId)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("scope", "repo read:user")
                .queryParam("state", state)
                .build().toUri();
    }

    // ── Callback ─────────────────────────────────────────────────────────────

    /**
     * Handles the OAuth callback: validates state, exchanges code for token, stores encrypted token.
     * Returns a redirect URI to the frontend.
     */
    public URI handleCallback(String code, String state) {
        String userId = validateState(state);

        String accessToken = exchangeCodeForToken(code);

        String githubLogin = fetchGithubLogin(accessToken);

        AppUser user = userRepo.findByUsername(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User not found"));
        user.setGithubLogin(githubLogin);
        user.setGithubAccessToken(aes.encrypt(accessToken));
        userRepo.save(user);

        log.info("GitHub account '{}' connected for user '{}'", githubLogin, userId);
        return URI.create(frontendUrl + "/settings?github=connected");
    }

    // ── Token decryption (for internal use by BRD pipeline) ──────────────────

    public String decryptToken(String encryptedToken) {
        return aes.decrypt(encryptedToken);
    }

    // ── Disconnect ───────────────────────────────────────────────────────────

    public void disconnect(String userId) {
        AppUser user = userRepo.findByUsername(userId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "User not found"));
        user.setGithubLogin(null);
        user.setGithubAccessToken(null);
        userRepo.save(user);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private String buildState(String userId) {
        String payload = userId + ":" + Instant.now().toEpochMilli();
        String sig = hmacSha256(payload, jwtSecret);
        String raw = payload + ":" + sig;
        return java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    private String validateState(String state) {
        try {
            String raw = new String(java.util.Base64.getUrlDecoder().decode(state), StandardCharsets.UTF_8);
            // format: userId:timestamp:sig
            int lastColon = raw.lastIndexOf(':');
            String payload = raw.substring(0, lastColon);
            String sig = raw.substring(lastColon + 1);
            String expected = hmacSha256(payload, jwtSecret);
            if (!expected.equals(sig)) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid OAuth state");
            }
            String[] parts = payload.split(":", 2);
            long ts = Long.parseLong(parts[1]);
            if (Instant.now().toEpochMilli() - ts > 10 * 60 * 1000) { // 10 min window
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OAuth state expired");
            }
            return parts[0]; // userId
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Malformed OAuth state");
        }
    }

    @SuppressWarnings("unchecked")
    private String exchangeCodeForToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        Map<String, String> body = Map.of(
                "client_id", clientId,
                "client_secret", clientSecret,
                "code", code,
                "redirect_uri", redirectUri
        );
        ResponseEntity<Map> resp = restTemplate.postForEntity(
                "https://github.com/login/oauth/access_token",
                new HttpEntity<>(body, headers), Map.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub token exchange failed");
        }
        String token = (String) resp.getBody().get("access_token");
        if (token == null || token.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "GitHub did not return an access token");
        }
        return token;
    }

    @SuppressWarnings("unchecked")
    private String fetchGithubLogin(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(java.util.List.of(MediaType.APPLICATION_JSON));
        ResponseEntity<Map> resp = restTemplate.exchange(
                "https://api.github.com/user", HttpMethod.GET,
                new HttpEntity<>(headers), Map.class);
        if (!resp.getStatusCode().is2xxSuccessful() || resp.getBody() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "Could not fetch GitHub user");
        }
        return (String) resp.getBody().get("login");
    }

    private static String hmacSha256(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("HMAC-SHA256 failed", e);
        }
    }
}
