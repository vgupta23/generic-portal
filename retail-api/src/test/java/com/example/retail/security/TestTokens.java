package com.example.retail.security;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mints RS256 tokens that look like the ones Keycloak issues for the demo realm. */
public final class TestTokens {

    public static final String ISSUER = "http://localhost:8080/realms/demo";
    public static final String SUB = "11111111-2222-3333-4444-555555555555";
    /** The key the test decoders trust. */
    public static final KeyPair TRUSTED = newKey();
    /** A different key: tokens signed with it must be rejected. */
    public static final KeyPair ROGUE = newKey();

    public static final List<String> BOTH_RETAIL = List.of("111111111:USA,RETAILUI,CRM", "222222222:USA,RETAILUI,CRM,DAM_OP,CRR");

    private String issuer = ISSUER;
    private String subject = SUB;
    private List<String> audience;
    private Instant expiry = Instant.now().plusSeconds(300);
    private KeyPair signer = TRUSTED;
    private final Map<String, Object> claims = new LinkedHashMap<>();

    private TestTokens(List<String> audience) { this.audience = audience; }

    /** Access token like the portal's: aud contains retail-ui and crm-ui, carries duns_roles. */
    public static TestTokens access(List<String> dunsRoles) {
        TestTokens t = new TestTokens(List.of("retail-ui", "crm-ui", "account"));
        t.claims.put("preferred_username", "testuser");
        t.claims.put("name", "Test User");
        t.claims.put("email", "testuser@example.com");
        t.claims.put("department", "Engineering");
        t.claims.put("employee_id", "E-1001");
        t.claims.put("roles", List.of("portal-admin"));
        if (dunsRoles != null) {
            t.claims.put("duns_roles", dunsRoles);
        }
        return t;
    }

    /** ID token issued to the portal client. */
    public static TestTokens id() {
        TestTokens t = new TestTokens(List.of("demo-ui"));
        t.claims.put("preferred_username", "testuser");
        t.claims.put("name", "Test User");
        t.claims.put("email", "testuser@example.com");
        return t;
    }

    public TestTokens issuer(String v) { this.issuer = v; return this; }
    public TestTokens subject(String v) { this.subject = v; return this; }
    public TestTokens audience(String... v) { this.audience = List.of(v); return this; }
    public TestTokens expiredMinutesAgo(int minutes) { this.expiry = Instant.now().minusSeconds(60L * minutes); return this; }
    public TestTokens signedBy(KeyPair k) { this.signer = k; return this; }
    public TestTokens claim(String name, Object value) { this.claims.put(name, value); return this; }
    public TestTokens without(String name) { this.claims.remove(name); return this; }

    public String build() {
        try {
            JWTClaimsSet.Builder b = new JWTClaimsSet.Builder()
                    .issuer(issuer)
                    .subject(subject)
                    .audience(new ArrayList<>(audience))
                    .issueTime(new Date())
                    .expirationTime(Date.from(expiry));
            claims.forEach(b::claim);
            SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("test-key").build(), b.build());
            jwt.sign(new RSASSASigner((RSAPrivateKey) signer.getPrivate()));
            return jwt.serialize();
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public static RSAPublicKey trustedPublicKey() { return (RSAPublicKey) TRUSTED.getPublic(); }

    private static KeyPair newKey() {
        try {
            KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
            g.initialize(2048);
            return g.generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
