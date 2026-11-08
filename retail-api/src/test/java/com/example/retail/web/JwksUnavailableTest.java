package com.example.retail.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.retail.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** When Keycloak's signing keys cannot be fetched the API must answer 503 (retry), not blame the caller with 401. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(JwksUnavailableTest.BrokenDecoders.class)
class JwksUnavailableTest {

    @TestConfiguration
    static class BrokenDecoders {
        @Bean JwtDecoder accessTokenDecoder() { return t -> { throw new JwtException("Couldn't retrieve remote JWK set"); }; }
        @Bean JwtDecoder idTokenDecoder() { return t -> { throw new JwtException("Couldn't retrieve remote JWK set"); }; }
    }

    @Autowired MockMvc mvc;

    @Test
    void validTokensButKeysUnreachableGives503() throws Exception {
        mvc.perform(get("/api/toys")
                        .header("Authorization", "Bearer " + TestTokens.access(TestTokens.BOTH_RETAIL).build())
                        .header("X-ID-Token", TestTokens.id().build()))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "5"))
                .andExpect(jsonPath("$.error").value("service_unavailable"));
    }

    @Test
    void noTokenIsStill401() throws Exception {
        mvc.perform(get("/api/toys")).andExpect(status().isUnauthorized());
    }

    @Test
    void healthStaysUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
    }
}
