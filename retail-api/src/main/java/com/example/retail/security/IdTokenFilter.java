package com.example.retail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Requires a second, valid OIDC token on every API call: the ID token in the {@code X-ID-Token} header.
 * It must verify (signature, issuer, expiry, audience) and belong to the same user as the access token
 * (equal {@code sub}). Runs after the bearer-token filter, so the access token has already been verified.
 */
public class IdTokenFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-ID-Token";
    public static final String REQUEST_ATTRIBUTE = "verifiedIdToken";

    private final JwtDecoder idTokenDecoder;
    private final ApiErrorWriter errors;

    public IdTokenFilter(JwtDecoder idTokenDecoder, ApiErrorWriter errors) {
        this.idTokenDecoder = idTokenDecoder;
        this.errors = errors;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth instanceof JwtAuthenticationToken access) {
            String raw = req.getHeader(HEADER);
            if (raw == null || raw.isBlank()) {
                errors.unauthorized(req, res, "ID token missing (send it in the " + HEADER + " header)");
                return;
            }
            Jwt id;
            try {
                id = idTokenDecoder.decode(raw.trim());
            } catch (BadJwtException e) {
                errors.unauthorized(req, res, "invalid ID token: " + e.getMessage());
                return;
            } catch (JwtException e) {
                errors.unavailable(req, res); // e.g. signing keys unreachable
                return;
            }
            if (!id.getSubject().equals(access.getToken().getSubject())) {
                errors.unauthorized(req, res, "ID token does not belong to the same user as the access token");
                return;
            }
            req.setAttribute(REQUEST_ATTRIBUTE, id);
        }
        chain.doFilter(req, res);
    }
}
