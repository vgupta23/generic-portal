package com.example.retail.security;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Settings under {@code retail.security.*} (see application.yml). */
@ConfigurationProperties("retail.security")
public record RetailSecurityProperties(
        String issuer,
        String jwkSetUri,
        String accessAudience,
        List<String> idAudiences,
        String requiredRole,
        String toyDuns,
        String flowerDuns) {

    /** Authority string granted for "role {@code role} under DUNS {@code duns}", e.g. {@code 111111111:RETAILUI}. */
    public static String authority(String duns, String role) {
        return duns + ":" + role;
    }
}
