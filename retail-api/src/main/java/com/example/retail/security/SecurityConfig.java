package com.example.retail.security;

import java.util.List;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableConfigurationProperties(RetailSecurityProperties.class)
class SecurityConfig {

    @Bean
    SecurityFilterChain apiSecurity(
            HttpSecurity http,
            RetailSecurityProperties p,
            @Qualifier("accessTokenDecoder") JwtDecoder accessTokenDecoder,
            @Qualifier("idTokenDecoder") JwtDecoder idTokenDecoder,
            ApiErrorWriter errors) throws Exception {

        String toyAuthority = RetailSecurityProperties.authority(p.toyDuns(), p.requiredRole());
        String flowerAuthority = RetailSecurityProperties.authority(p.flowerDuns(), p.requiredRole());

        http.csrf(AbstractHttpConfigurer::disable)
            .cors(Customizer.withDefaults())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(a -> a
                .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                // Each catalog is owned by one DUNS: the caller needs RETAILUI under THAT DUNS.
                .requestMatchers("/api/toys", "/api/toys/**").hasAuthority(toyAuthority)
                .requestMatchers("/api/flowers", "/api/flowers/**").hasAuthority(flowerAuthority)
                // /api/me: RETAILUI under any DUNS.
                .requestMatchers("/api/me").access((auth, ctx) -> new AuthorizationDecision(
                        auth.get().getAuthorities().stream()
                            .anyMatch(g -> g.getAuthority().endsWith(":" + p.requiredRole()))))
                .anyRequest().denyAll())
            .oauth2ResourceServer(o -> o
                .jwt(j -> j.decoder(accessTokenDecoder).jwtAuthenticationConverter(dunsConverter()))
                .authenticationEntryPoint(errors)
                .accessDeniedHandler(errors))
            .exceptionHandling(e -> e.authenticationEntryPoint(errors).accessDeniedHandler(errors))
            .addFilterBefore(new VerificationUnavailableFilter(errors), BearerTokenAuthenticationFilter.class)
            .addFilterAfter(new IdTokenFilter(idTokenDecoder, errors), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /** Access-token claim {@code duns_roles} becomes authorities such as {@code 111111111:RETAILUI}. */
    static Converter<Jwt, AbstractAuthenticationToken> dunsConverter() {
        return jwt -> {
            List<GrantedAuthority> authorities = DunsRoles.parse(jwt.getClaim("duns_roles")).entrySet().stream()
                    .flatMap(e -> e.getValue().stream()
                            .map(role -> (GrantedAuthority) new SimpleGrantedAuthority(
                                    RetailSecurityProperties.authority(e.getKey(), role))))
                    .collect(Collectors.toList());
            String name = jwt.getClaimAsString("preferred_username");
            return new JwtAuthenticationToken(jwt, authorities, name != null ? name : jwt.getSubject());
        };
    }
}
