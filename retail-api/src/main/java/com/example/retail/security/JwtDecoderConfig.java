package com.example.retail.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/**
 * Production decoders: signatures are verified (RS256) against Keycloak's JWKS endpoint, which is fetched lazily,
 * cached, and re-fetched on an unknown {@code kid} (key rotation). Tests supply their own decoders.
 */
@Configuration
@Profile("!test")
class JwtDecoderConfig {

    @Bean
    JwtDecoder accessTokenDecoder(RetailSecurityProperties p) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(p.jwkSetUri()).build();
        decoder.setJwtValidator(TokenValidators.forAudiences(p.issuer(), java.util.List.of(p.accessAudience())));
        return decoder;
    }

    @Bean
    JwtDecoder idTokenDecoder(RetailSecurityProperties p) {
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(p.jwkSetUri()).build();
        decoder.setJwtValidator(TokenValidators.forAudiences(p.issuer(), p.idAudiences()));
        return decoder;
    }
}
