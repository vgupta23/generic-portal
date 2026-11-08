package com.example.retail.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Sits in front of the bearer-token filter. When the access token cannot be verified for infrastructure reasons
 * (Spring raises {@link AuthenticationServiceException}, e.g. the JWKS endpoint is down) the caller gets 503, not a
 * misleading 401 on a perfectly good token.
 */
class VerificationUnavailableFilter extends OncePerRequestFilter {

    private final ApiErrorWriter errors;

    VerificationUnavailableFilter(ApiErrorWriter errors) { this.errors = errors; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(req, res);
        } catch (AuthenticationServiceException e) {
            errors.unavailable(req, res);
        }
    }
}
