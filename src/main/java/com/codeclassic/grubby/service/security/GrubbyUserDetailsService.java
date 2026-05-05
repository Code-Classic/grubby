package com.codeclassic.grubby.service.security;

import com.codeclassic.grubby.repository.AppUserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.*;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * P1 — Bridges our AppUser entity into Spring Security's UserDetails model.
 * Roles are stored as a comma-separated string in the DB (e.g. "ROLE_USER,ROLE_ADMIN").
 */
@Service
@RequiredArgsConstructor
public class GrubbyUserDetailsService implements UserDetailsService {

    private final AppUserRepository appUserRepository;

    @Override
    public UserDetails loadUserByUsername(String username) throws UsernameNotFoundException {
        return appUserRepository.findByUsername(username)
                .map(u -> User.builder()
                        .username(u.getUsername())
                        .password(u.getPasswordHash())
                        .disabled(!u.isEnabled())
                        .authorities(
                                Arrays.stream(u.getRoles().split(","))
                                        .map(String::trim)
                                        .filter(r -> !r.isBlank())
                                        .map(SimpleGrantedAuthority::new)
                                        .collect(Collectors.toList())
                        )
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }
}
