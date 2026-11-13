package com.example.retail.web;

import com.example.retail.security.DunsRoles;
import com.example.retail.security.IdTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Echoes the identity the API derived from the two verified tokens (handy for debugging claims). */
@RestController
@RequestMapping("/api/me")
class MeController {

    @GetMapping
    Map<String, Object> me(@AuthenticationPrincipal Jwt access, HttpServletRequest request) {
        Jwt id = (Jwt) request.getAttribute(IdTokenFilter.REQUEST_ATTRIBUTE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", access.getClaimAsString("preferred_username"));
        body.put("name", id != null ? id.getClaimAsString("name") : null);
        body.put("email", id != null ? id.getClaimAsString("email") : null);
        body.put("subject", access.getSubject());
        body.put("department", access.getClaimAsString("department"));
        body.put("employeeId", access.getClaimAsString("employee_id"));
        body.put("clientId", access.getClaimAsString("azp"));
        body.put("duns", access.getClaimAsString("duns"));
        body.put("scope", access.getClaimAsString("scope"));
        body.put("roles", access.getClaim("roles"));
        body.put("dunsRoles", DunsRoles.fromToken(access));
        body.put("accessTokenAudience", access.getAudience());
        body.put("idTokenAudience", id != null ? id.getAudience() : null);
        body.put("accessTokenExpires", access.getExpiresAt());
        return body;
    }
}
