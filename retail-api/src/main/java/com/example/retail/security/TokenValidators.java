package com.example.retail.security;

import java.util.Collection;
import java.util.List;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtValidators;

/** Claim validation shared by production decoders and the test decoders. */
public final class TokenValidators {

    private TokenValidators() {}

    /** Expiry / not-before (60 s clock skew), issuer, and audience. */
    public static OAuth2TokenValidator<Jwt> forAudiences(String issuer, Collection<String> acceptedAudiences) {
        return new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(issuer), audience(acceptedAudiences));
    }

    static OAuth2TokenValidator<Jwt> audience(Collection<String> accepted) {
        return jwt -> {
            List<String> aud = jwt.getAudience();
            if (aud != null && aud.stream().anyMatch(accepted::contains)) {
                return OAuth2TokenValidatorResult.success();
            }
            return OAuth2TokenValidatorResult.failure(
                    new OAuth2Error("invalid_token", "Token audience " + aud + " is not accepted by this API", null));
        };
    }
}
