package com.example.retail.security;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Parses the {@code duns_roles} claim. Each value has the form {@code DUNS:ROLE,ROLE,...}, for example
 * {@code 111111111:USA,RETAILUI,CRM}.
 */
public final class DunsRoles {

    private DunsRoles() {}

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
