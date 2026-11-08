package com.example.retail.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.retail.security.TestJwtConfig;
import com.example.retail.security.TestTokens;
import java.util.List;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Every refusal must be a JSON "access_denied": 401 for a missing/invalid token, 403 when the token is valid but the
 * user lacks RETAILUI for the DUNS that owns the data.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class RetailApiAccessDeniedTest {

    @Autowired MockMvc mvc;

    private ResultActions call(String path, String access, String id) throws Exception {
        var req = get(path);
        if (access != null) req.header("Authorization", "Bearer " + access);
        if (id != null) req.header("X-ID-Token", id);
        return mvc.perform(req);
    }

    private static String goodId() { return TestTokens.id().build(); }

    private static String goodAccess() { return TestTokens.access(TestTokens.BOTH_RETAIL).build(); }

    private static void denied(ResultActions r, int status) throws Exception {
        r.andExpect(status().is(status))
         .andExpect(jsonPath("$.error").value("access_denied"))
         .andExpect(jsonPath("$.status").value(status))
         .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Nested
    class MissingOrInvalidToken {

        @Test
        void noAuthorizationHeader() throws Exception {
            denied(call("/api/toys", null, goodId()), 401);
        }

        @Test
        void noTokensAtAll() throws Exception {
            call("/api/flowers", null, null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(header().string("WWW-Authenticate", org.hamcrest.Matchers.startsWith("Bearer")))
                    .andExpect(jsonPath("$.error").value("access_denied"));
        }

        @Test
        void garbageBearerToken() throws Exception {
            denied(call("/api/toys", "not-a-jwt", goodId()), 401);
        }

        @Test
        void wrongAuthScheme() throws Exception {
            denied(mvc.perform(get("/api/toys").header("Authorization", "Basic dXNlcjpwYXNz").header("X-ID-Token", goodId())), 401);
        }

        @Test
        void expiredAccessToken() throws Exception {
            denied(call("/api/toys", TestTokens.access(TestTokens.BOTH_RETAIL).expiredMinutesAgo(10).build(), goodId()), 401);
        }

        @Test
        void accessTokenSignedWithUntrustedKey() throws Exception {
            denied(call("/api/toys", TestTokens.access(TestTokens.BOTH_RETAIL).signedBy(TestTokens.ROGUE).build(), goodId()), 401);
        }

        @Test
        void accessTokenFromWrongIssuer() throws Exception {
            denied(call("/api/toys", TestTokens.access(TestTokens.BOTH_RETAIL).issuer("http://evil.example/realms/demo").build(), goodId()), 401);
        }

        @Test
        void accessTokenNotIssuedForThisApi() throws Exception {
            denied(call("/api/toys", TestTokens.access(TestTokens.BOTH_RETAIL).audience("account").build(), goodId()), 401);
        }

        @Test
        void unsignedAlgNoneToken() throws Exception {
            String header = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString("{\"alg\":\"none\"}".getBytes());
            String payload = goodAccess().split("\\.")[1];
            denied(call("/api/toys", header + "." + payload + ".", goodId()), 401);
        }
    }

    @Nested
    class IdTokenProblems {

        @Test
        void idTokenMissing() throws Exception {
            call("/api/toys", goodAccess(), null)
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("ID token missing")));
        }

        @Test
        void idTokenGarbage() throws Exception {
            denied(call("/api/toys", goodAccess(), "garbage"), 401);
        }

        @Test
        void idTokenExpired() throws Exception {
            denied(call("/api/toys", goodAccess(), TestTokens.id().expiredMinutesAgo(10).build()), 401);
        }

        @Test
        void idTokenSignedWithUntrustedKey() throws Exception {
            denied(call("/api/toys", goodAccess(), TestTokens.id().signedBy(TestTokens.ROGUE).build()), 401);
        }

        @Test
        void idTokenForWrongClient() throws Exception {
            denied(call("/api/toys", goodAccess(), TestTokens.id().audience("some-other-client").build()), 401);
        }

        @Test
        void idTokenBelongsToDifferentUser() throws Exception {
            denied(call("/api/toys", goodAccess(), TestTokens.id().subject("99999999-0000-0000-0000-000000000000").build()), 401);
        }
    }

    @Nested
    class MissingRole {

        @Test
        void noRetailuiAnywhere() throws Exception {
            String access = TestTokens.access(List.of("111111111:USA,CRM", "222222222:USA,DAM_OP")).build();
            denied(call("/api/toys", access, goodId()), 403);
            denied(call("/api/flowers", access, goodId()), 403);
            denied(call("/api/me", access, goodId()), 403);
        }

        @Test
        void noDunsRolesClaimAtAll() throws Exception {
            denied(call("/api/toys", TestTokens.access(null).build(), goodId()), 403);
        }

        @Test
        void retailuiOnlyForToyShop() throws Exception {
            String access = TestTokens.access(List.of("111111111:USA,RETAILUI", "222222222:USA,CRM")).build();
            call("/api/toys", access, goodId()).andExpect(status().isOk());
            call("/api/me", access, goodId()).andExpect(status().isOk());
            denied(call("/api/flowers", access, goodId()), 403);
            call("/api/flowers", access, goodId())
                    .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("DUNS 222222222")));
        }

        @Test
        void retailuiOnlyForFlowerShop() throws Exception {
            String access = TestTokens.access(List.of("222222222:RETAILUI")).build();
            call("/api/flowers", access, goodId()).andExpect(status().isOk());
            denied(call("/api/toys", access, goodId()), 403);
        }

        @Test
        void roleOnWrongDunsDoesNotCount() throws Exception {
            // RETAILUI exists but under a DUNS that owns neither catalog.
            String access = TestTokens.access(List.of("333333333:RETAILUI")).build();
            denied(call("/api/toys", access, goodId()), 403);
            denied(call("/api/flowers", access, goodId()), 403);
        }

        @Test
        void roleNameIsCaseSensitive() throws Exception {
            String access = TestTokens.access(List.of("111111111:retailui")).build();
            denied(call("/api/toys", access, goodId()), 403);
        }
    }

    @Test
    void unknownPathIsDeniedNotLeaked() throws Exception {
        denied(call("/api/secret", goodAccess(), goodId()), 403);
        denied(call("/api/secret", null, null), 401);
    }
}
