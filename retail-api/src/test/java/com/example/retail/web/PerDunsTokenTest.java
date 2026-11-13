package com.example.retail.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.retail.security.TestJwtConfig;
import com.example.retail.security.TestTokens;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/** Tokens from the per-DUNS clients: claim {@code duns} plus roles under {@code resource_access.duns-<DUNS>}. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class PerDunsTokenTest {

    @Autowired MockMvc mvc;

    private static String access(String duns, String... roles) {
        TestTokens t = TestTokens.access(null).claim("duns", duns).claim("azp", "retail-duns-" + duns);
        if (roles.length > 0) {
            t.claim("resource_access", Map.of("duns-" + duns, Map.of("roles", List.of(roles))));
        }
        return t.build();
    }

    private ResultActions call(String path, String access) throws Exception {
        return mvc.perform(get(path).header("Authorization", "Bearer " + access)
                .header("X-ID-Token", TestTokens.id().build()));
    }

    @Test
    void toyDunsWithRetailUiReadsToys() throws Exception {
        call("/api/toys", access("111111111", "RETAILUI")).andExpect(status().isOk());
    }

    @Test
    void toyDunsTokenCannotReadFlowers() throws Exception {
        call("/api/flowers", access("111111111", "RETAILUI")).andExpect(status().isForbidden());
    }

    @Test
    void flowerDunsTokenCannotReadToys() throws Exception {
        call("/api/toys", access("222222222", "RETAILUI")).andExpect(status().isForbidden());
        call("/api/flowers", access("222222222", "RETAILUI")).andExpect(status().isOk());
    }

    @Test
    void noRoleRequestedByScopeMeansForbidden() throws Exception {
        call("/api/toys", access("111111111")).andExpect(status().isForbidden());
    }

    @Test
    void rolesOtherThanRetailUiDoNotGrantAccess() throws Exception {
        call("/api/toys", access("111111111", "CRM")).andExpect(status().isForbidden());
    }

    @Test
    void meShowsOnlyTheRequestedDunsAndRole() throws Exception {
        call("/api/me", access("111111111", "RETAILUI"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duns").value("111111111"))
                .andExpect(jsonPath("$.clientId").value("retail-duns-111111111"))
                .andExpect(jsonPath("$.dunsRoles['111111111'][0]").value("RETAILUI"))
                .andExpect(jsonPath("$.dunsRoles['222222222']").doesNotExist());
    }
}
