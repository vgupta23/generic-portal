package com.example.retail.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class DunsRolesTest {

    @Test
    void parsesOneEntryPerDuns() {
        Map<String, Set<String>> m = DunsRoles.parse(List.of("111111111:USA,RETAILUI,CRM", "222222222:USA,DAM_OP,CRR"));
        assertThat(m).containsOnlyKeys("111111111", "222222222");
        assertThat(m.get("111111111")).containsExactly("USA", "RETAILUI", "CRM");
        assertThat(m.get("222222222")).containsExactly("USA", "DAM_OP", "CRR");
    }

    @Test
    void toleratesBracesAndWhitespace() {
        assertThat(DunsRoles.parse(List.of("111111111 : {USA, RETAILUI}")).get("111111111"))
                .containsExactly("USA", "RETAILUI");
    }

    @Test
    void acceptsSingleStringClaim() {
        assertThat(DunsRoles.parse("111111111:RETAILUI")).containsKey("111111111");
    }

    @Test
    void nullAndMalformedYieldNothing() {
        assertThat(DunsRoles.parse(null)).isEmpty();
        assertThat(DunsRoles.parse(List.of("no-colon", ":ROLE"))).isEmpty();
    }
}
