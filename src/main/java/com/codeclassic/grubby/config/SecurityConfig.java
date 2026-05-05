package com.codeclassic.grubby.config;

import com.codeclassic.grubby.service.security.GrubbyUserDetailsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

/**
 * P1 — Spring Security configuration.
 *
 * Public routes  : POST /api/v1/auth/register, POST /api/v1/auth/login,
 *                  GET  /actuator/health, GET /swagger-ui/**, GET /v3/api-docs/**
 * Admin routes   : /api/v1/admin/** → ROLE_ADMIN only
 * All other /api : authenticated (any role)
 *
 * P7 — CORS is configured here from the cors.allowed-origins property (no wildcard default).
 *       The @CrossOrigin on BrdController is removed so this single source-of-truth applies.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final GrubbyUserDetailsService userDetailsService;

    /** P7 — must be set explicitly; no default wildcard */
    @Value("${cors.allowed-origins}")
    private String allowedOriginsRaw;

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                // Auth endpoints — always public
                .requestMatchers(HttpMethod.POST,
                        "/api/v1/auth/register",
                        "/api/v1/auth/login",
                        "/api/v1/auth/refresh").permitAll()
                // GitHub OAuth callback is public (arrives from GitHub redirect)
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/github/callback").permitAll()
                // Public shared timelines — accessible without auth via share token
                .requestMatchers(HttpMethod.GET, "/api/v1/timeline/public/**").permitAll()
                // Health check — public (avoids alerting noise)
                .requestMatchers(HttpMethod.GET, "/actuator/health").permitAll()
                // Swagger — public (restrict in prod if needed)
                .requestMatchers("/swagger-ui/**", "/swagger-ui.html", "/v3/api-docs/**").permitAll()
                // Admin allowlist management — ROLE_ADMIN only
                .requestMatchers("/api/v1/admin/**").hasRole("ADMIN")
                // Everything else requires authentication
                .anyRequest().authenticated()
            )
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class)
            .authenticationProvider(authenticationProvider());

        return http.build();
    }

    /** P7 — CORS built from the cors.allowed-origins property, no wildcard fallback. */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        List<String> origins = Arrays.stream(allowedOriginsRaw.split(","))
                .map(String::trim)
                .filter(s -> !s.isBlank())
                .toList();
        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type", "X-User-Id"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
