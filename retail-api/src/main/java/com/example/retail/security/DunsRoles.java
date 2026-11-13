package com.example.retail.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Parses the {@code duns_roles} claim. Each value has the form {@code DUNS:ROLE,ROLE,...}, for example
 * {@code 111111111:USA,RETAILUI,CRM}.
 */
public final class DunsRoles {

    private DunsRoles() {}

    /**
     * DUNS roles of an access token. Per-DUNS tokens (client {@code retail-duns-<DUNS>}) carry a {@code duns} claim and
     * only the roles requested by scope under {@code resource_access.duns-<DUNS>.roles}; portal tokens carry
     * {@code duns_roles}. Both forms are accepted, and a per-DUNS token can never grant roles of another DUNS.
     */
    public static Map<String, Set<String>> fromToken(Jwt jwt) {
        Map<String, Set<String>> result = parse(jwt.getClaim("duns_roles"));
        String duns = jwt.getClaimAsString("duns");
        if (duns != null && !duns.isBlank()) {
            Object resourceAccess = jwt.getClaim("resource_access");
            if (resourceAccess instanceof Map<?, ?> ra && ra.get("duns-" + duns) instanceof Map<?, ?> res
                    && res.get("roles") instanceof Collection<?> roles) {
                Set<String> granted = result.computeIfAbsent(duns, d -> new LinkedHashSet<>());
                roles.forEach(r -> granted.add(String.valueOf(r)));
            }
        }
        return result;
    }

    public static Map<String, Set<String>> parse(Object claim) {
        Map<String, Set<String>> result = new LinkedHashMap<>();
        if (claim == null) {
            return result;
        }
        Collection<?> entries = claim instanceof Collection<?> c ? c : java.util.List.of(claim);
        for (Object entry : entries) {
            String text = String.valueOf(entry).replace("{", "").replace("}", "");
            int colon = text.indexOf(':');
            if (colon < 1) {
                continue; // malformed entry: ignore rather than guess
            }
            String duns = text.substring(0, colon).trim();
            Set<String> roles = result.computeIfAbsent(duns, d -> new LinkedHashSet<>());
            for (String role : text.substring(colon + 1).split(",")) {
                if (!role.isBlank()) {
                    roles.add(role.trim());
                }
            }
        }
        return result;
    }
}
