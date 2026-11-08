package com.example.retail.security;

import java.util.List;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Test replacement for {@link JwtDecoderConfig}: same claim validation (issuer, expiry, audience), but signatures are
 * checked against the in-memory test key instead of Keycloak's JWKS endpoint.
 */
@TestConfiguration
public class TestJwtConfig {

    @Bean
    JwtDecoder accessTokenDecoder(RetailSecurityProperties p) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(TestTokens.trustedPublicKey()).build();
        d.setJwtValidator(TokenValidators.forAudiences(p.issuer(), List.of(p.accessAudience())));
        return d;
    }

    @Bean
    JwtDecoder idTokenDecoder(RetailSecurityProperties p) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(TestTokens.trustedPublicKey()).build();
        d.setJwtValidator(TokenValidators.forAudiences(p.issuer(), p.idAudiences()));
        return d;
    }
}
