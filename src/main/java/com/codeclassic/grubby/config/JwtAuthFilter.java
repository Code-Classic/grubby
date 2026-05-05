package com.codeclassic.grubby.config;

import com.codeclassic.grubby.util.JwtUtils;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/**
 * P1 — Validates the Bearer JWT on every request and populates SecurityContext.
 * Requests without a valid token reach protected routes and are rejected by the
 * SecurityFilterChain with 401.
 */
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtils jwtUtils;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain chain)
            throws ServletException, IOException {

        String token = null;

        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            token = header.substring(7);
        } else {
            // Fallback: browser file downloads (window.open) cannot set headers,
            // so the frontend passes the JWT as a ?token= query param instead.
            String queryToken = request.getParameter("token");
            if (queryToken != null && !queryToken.isBlank()) {
                token = queryToken;
            }
        }

        if (token == null || !jwtUtils.isValid(token)) {
            chain.doFilter(request, response);
            return;
        }

        String username = jwtUtils.extractUsername(token);
        String roles    = jwtUtils.extractRoles(token);

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            // Build authorities directly from the token claim to avoid a DB hit on every request
            List<SimpleGrantedAuthority> authorities = Arrays.stream((roles != null ? roles : "ROLE_USER").split(","))
                    .map(String::trim)
                    .filter(r -> !r.isBlank())
                    .map(SimpleGrantedAuthority::new)
                    .collect(Collectors.toList());

            // Wrap in a UserDetails so @AuthenticationPrincipal UserDetails resolves correctly
            UserDetails userDetails = User.withUsername(username)
                    .password("")
                    .authorities(authorities)
                    .build();

            UsernamePasswordAuthenticationToken auth =
                    new UsernamePasswordAuthenticationToken(userDetails, null, authorities);
            auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
            SecurityContextHolder.getContext().setAuthentication(auth);
        }

        chain.doFilter(request, response);
    }
}
