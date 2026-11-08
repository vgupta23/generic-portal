package com.example.retail.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Turns every authentication/authorization failure into the same JSON "access denied" body:
 * 401 for a missing/invalid token, 403 for a valid token without the required role.
 */
@Component
public class ApiErrorWriter implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper mapper;
    private final RetailSecurityProperties props;

    ApiErrorWriter(ObjectMapper mapper, RetailSecurityProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    @Override
    public void commence(HttpServletRequest req, HttpServletResponse res, AuthenticationException ex) throws IOException {
        String detail = ex instanceof OAuth2AuthenticationException o && o.getError().getDescription() != null
                ? o.getError().getDescription()
                : "a valid access token is required";
        write(req, res, HttpStatus.UNAUTHORIZED, "Access denied: " + detail);
    }

    @Override
    public void handle(HttpServletRequest req, HttpServletResponse res, AccessDeniedException ex) throws IOException {
        String uri = req.getRequestURI();
        String duns = uri.startsWith("/api/toys") ? props.toyDuns() : uri.startsWith("/api/flowers") ? props.flowerDuns() : null;
        String msg = duns != null
                ? "Access denied: role " + props.requiredRole() + " is required for DUNS " + duns
                : "Access denied: role " + props.requiredRole() + " is required";
        write(req, res, HttpStatus.FORBIDDEN, msg);
    }

    /** Used by the ID-token filter for failures it detects itself. */
    public void unauthorized(HttpServletRequest req, HttpServletResponse res, String detail) throws IOException {
        write(req, res, HttpStatus.UNAUTHORIZED, "Access denied: " + detail);
    }

    /** Token verification could not be performed (e.g. Keycloak's signing keys are unreachable): not the caller's fault. */
    public void unavailable(HttpServletRequest req, HttpServletResponse res) throws IOException {
        res.setHeader("Retry-After", "5");
        write(req, res, HttpStatus.SERVICE_UNAVAILABLE, "Token verification is temporarily unavailable (cannot reach the identity provider's signing keys). Retry shortly.", "service_unavailable");
    }

    private void write(HttpServletRequest req, HttpServletResponse res, HttpStatus status, String message) throws IOException {
        write(req, res, status, message, "access_denied");
    }

    private void write(HttpServletRequest req, HttpServletResponse res, HttpStatus status, String message, String error) throws IOException {
        if (status == HttpStatus.UNAUTHORIZED) {
            res.setHeader("WWW-Authenticate", "Bearer realm=\"retail-api\"");
        }
        res.setStatus(status.value());
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", status.value());
        body.put("error", error);
        body.put("message", message);
        body.put("path", req.getRequestURI());
        mapper.writeValue(res.getOutputStream(), body);
    }
}
