package com.example.retail.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.example.retail.security.TestJwtConfig;
import com.example.retail.security.TestTokens;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Happy paths: a user with RETAILUI under both DUNS gets the catalogs. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestJwtConfig.class)
class RetailApiEndpointsTest {

    @Autowired MockMvc mvc;

    static MockHttpServletRequestBuilder authed(String path) {
        return get(path)
                .header("Authorization", "Bearer " + TestTokens.access(TestTokens.BOTH_RETAIL).build())
                .header("X-ID-Token", TestTokens.id().build());
    }

    @Test
    void listToysReturnsToyShopCatalog() throws Exception {
        mvc.perform(authed("/api/toys"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duns").value("111111111"))
                .andExpect(jsonPath("$.company").value("Toy Shop"))
                .andExpect(jsonPath("$.count").value(6))
                .andExpect(jsonPath("$.items[0].id").value("t1"))
                .andExpect(jsonPath("$.items[0].name").value("Teddy Bear"))
                .andExpect(jsonPath("$.items[0].price").value(19.99));
    }

    @Test
    void getToyById() throws Exception {
        mvc.perform(authed("/api/toys/t5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Robot Kit"))
                .andExpect(jsonPath("$.stock").value(15));
    }

    @Test
    void unknownToyIs404() throws Exception {
        mvc.perform(authed("/api/toys/nope"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("not_found"));
    }

    @Test
    void listFlowersReturnsFlowerShopCatalog() throws Exception {
        mvc.perform(authed("/api/flowers"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.duns").value("222222222"))
                .andExpect(jsonPath("$.company").value("Flower Shop"))
                .andExpect(jsonPath("$.count").value(6))
                .andExpect(jsonPath("$.items[0].name").value("Red Roses (dozen)"));
    }

    @Test
    void getFlowerById() throws Exception {
        mvc.perform(authed("/api/flowers/f3"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Sunflowers (6)"))
                .andExpect(jsonPath("$.color").value("Yellow"));
    }

    @Test
    void unknownFlowerIs404() throws Exception {
        mvc.perform(authed("/api/flowers/nope")).andExpect(status().isNotFound());
    }

    @Test
    void meEchoesVerifiedClaims() throws Exception {
        mvc.perform(authed("/api/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("testuser"))
                .andExpect(jsonPath("$.name").value("Test User"))
                .andExpect(jsonPath("$.department").value("Engineering"))
                .andExpect(jsonPath("$.dunsRoles['111111111']").isArray())
                .andExpect(jsonPath("$.dunsRoles['222222222'][1]").value("RETAILUI"));
    }

    @Test
    void healthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }
}
