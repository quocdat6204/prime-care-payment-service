package com.example.payment.security;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

public final class Access {

    private Access() {}

    public static Authentication authentication() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required");
        }
        return auth;
    }

    public static String subject() {
        return authentication().getName();
    }

    public static Long userId() {
        Authentication auth = authentication();
        if (auth.getPrincipal() instanceof Jwt jwt) {
            Object idClaim = jwt.getClaim("id");
            if (idClaim instanceof Number n) {
                return n.longValue();
            }
            if (idClaim instanceof String s && !s.isBlank()) {
                try {
                    return Long.parseLong(s);
                } catch (NumberFormatException ignored) {}
            }
        }
        try {
            return Long.parseLong(auth.getName());
        } catch (NumberFormatException e) {
            throw new AccessDeniedException("User ID must be a numeric value");
        }
    }

    public static boolean hasRole(String role) {
        return authentication().getAuthorities().stream()
                .anyMatch(a -> a.getAuthority().equals("ROLE_" + role));
    }

    public static void require(boolean allowed) {
        if (!allowed) {
            throw new AccessDeniedException("You do not have access to this resource");
        }
    }
}
