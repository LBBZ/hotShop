package com.real.security.util;

import com.real.security.config.SecurityProperties;
import com.real.security.entity.CustomUserDetails;
import com.real.security.identity.IdentityType;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.PrematureJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JwtTokenUtilTest {
    private static final Instant NOW = Instant.parse("2030-01-02T03:04:05Z");
    private static final long USER_ID = 9_007_199_254_740_993L;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    @TempDir
    static Path keys;
    private static KeyPair keyPair;
    private SecurityProperties properties;
    private JwtTokenUtil tokens;

    @BeforeAll
    static void createSigningKeys() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        keyPair = generator.generateKeyPair();
        writePem("private.pem", "PRIVATE KEY", keyPair.getPrivate().getEncoded());
        writePem("public.pem", "PUBLIC KEY", keyPair.getPublic().getEncoded());
    }

    @BeforeEach
    void configureDomains() {
        properties = new SecurityProperties();
        for (IdentityType identity : IdentityType.values()) {
            var domain = properties.domain(identity);
            domain.setPrivateKeyPath(keys.resolve("private.pem").toString());
            domain.setVerificationKeyPaths(Map.of(domain.getActiveKid(), keys.resolve("public.pem").toString()));
        }
        properties.getClientAssertion().setVerificationKeyPaths(
                Map.of("client-key", keys.resolve("public.pem").toString()));
        tokens = new JwtTokenUtil(properties, JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void issuedUserTokenPreservesStringAudienceLargeSubjectAndRs256() throws Exception {
        var issued = tokens.issueUserAccess(new CustomUserDetails(USER_ID, "alice", "", List.of()));
        JsonNode header = decode(issued.value(), 0);
        JsonNode claims = decode(issued.value(), 1);
        assertThat(header.get("alg").stringValue()).isEqualTo("RS256");
        assertThat(header.get("typ").stringValue()).isEqualTo(properties.getUser().getType());
        assertThat(claims.get("aud").isString()).isTrue();
        assertThat(claims.get("aud").stringValue()).isEqualTo(properties.getUser().getAudience());
        assertThat(claims.get("sub").stringValue()).isEqualTo(Long.toString(USER_ID));
        assertThat(claims.get("iat").longValue()).isEqualTo(NOW.getEpochSecond());
        assertThat(tokens.validate(issued.value(), IdentityType.USER_ACCESS).subjectUserId()).isEqualTo(USER_ID);
        assertThat(tokens.validate(issued.value(), IdentityType.USER_ACCESS).jti()).isEqualTo(issued.jti());
        assertThat(validSignature(issued.value())).isTrue();
    }

    @Test
    void administratorAndDelegationKeepSeparateIdentityClaims() {
        var administrator = tokens.issueAdministratorAccess(new CustomUserDetails(3L, "admin", "", List.of()));
        assertThat(tokens.validate(administrator.value(), IdentityType.ADMINISTRATOR_ACCESS).subjectUserId())
                .isEqualTo(3L);
        assertThatThrownBy(() -> tokens.validate(administrator.value(), IdentityType.USER_ACCESS))
                .isInstanceOf(JwtException.class);
        var delegation = tokens.issueAgentDelegation(USER_ID, "alice", properties.getClientAssertion().getClientId(),
                Set.of("order:read", "order:write"));
        var validated = tokens.validate(delegation.value(), IdentityType.AGENT_DELEGATION);
        assertThat(validated.scopes()).containsExactlyInAnyOrder("order:read", "order:write");
        assertThat(validated.authorizedParty()).isEqualTo(properties.getClientAssertion().getClientId());
        assertThat(decode(delegation.value(), 1).has("authorities")).isFalse();
    }

    @Test
    void verifiesPreMigrationStringAudienceWireFormatSignedWithoutJjwt() throws Exception {
        var validated = tokens.validate(sign(header(), userClaims()), IdentityType.USER_ACCESS);
        assertThat(validated.subjectUserId()).isEqualTo(USER_ID);
        assertThat(validated.jti()).isEqualTo("legacy-jti");
    }

    @ParameterizedTest
    @ValueSource(strings = {"iss", "aud", "token_use", "sub", "authorities"})
    void rejectsWrongSecurityClaims(String claim) throws Exception {
        var claims = userClaims();
        claims.put(claim, "unexpected");
        String token = sign(header(), claims);
        assertThatThrownBy(() -> tokens.validate(token, IdentityType.USER_ACCESS)).isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsAdditionalAudiencesInsteadOfRelaxingTheDomainBoundary() throws Exception {
        var claims = userClaims();
        claims.put("aud", List.of(properties.getUser().getAudience(), "another-api"));
        String token = sign(header(), claims);
        assertThatThrownBy(() -> tokens.validate(token, IdentityType.USER_ACCESS))
                .isInstanceOf(MalformedJwtException.class);
    }

    @Test
    void rejectsMissingRequiredClaimsAndExcessiveLifetime() throws Exception {
        for (String missing : List.of("iat", "nbf", "exp", "jti")) {
            var claims = userClaims();
            claims.remove(missing);
            String token = sign(header(), claims);
            assertThatThrownBy(() -> tokens.validate(token, IdentityType.USER_ACCESS)).isInstanceOf(JwtException.class);
        }
        var claims = userClaims();
        claims.put("exp", NOW.plusSeconds(1000).getEpochSecond());
        String token = sign(header(), claims);
        assertThatThrownBy(() -> tokens.validate(token, IdentityType.USER_ACCESS))
                .isInstanceOf(MalformedJwtException.class);
    }

    @Test
    void expirationAndNotBeforeUseTheSameInjectedClockAndSkew() throws Exception {
        var claims = userClaims();
        claims.put("iat", NOW.minusSeconds(100).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(100).getEpochSecond());
        claims.put("exp", NOW.minusSeconds(29).getEpochSecond());
        assertThat(tokens.validate(sign(header(), claims), IdentityType.USER_ACCESS).subjectUserId()).isEqualTo(USER_ID);
        claims.put("exp", NOW.minusSeconds(31).getEpochSecond());
        String expired = sign(header(), claims);
        assertThatThrownBy(() -> tokens.validate(expired, IdentityType.USER_ACCESS)).isInstanceOf(ExpiredJwtException.class);
        claims = userClaims();
        claims.put("iat", NOW.plusSeconds(29).getEpochSecond());
        claims.put("nbf", NOW.plusSeconds(29).getEpochSecond());
        assertThat(tokens.validate(sign(header(), claims), IdentityType.USER_ACCESS).subjectUserId()).isEqualTo(USER_ID);
        claims.put("nbf", NOW.plusSeconds(31).getEpochSecond());
        String premature = sign(header(), claims);
        assertThatThrownBy(() -> tokens.validate(premature, IdentityType.USER_ACCESS)).isInstanceOf(PrematureJwtException.class);
    }

    @Test
    void rejectsDifferentAlgorithmUnknownKidAndInvalidSignature() throws Exception {
        var header = header();
        header.put("alg", "RS512");
        String otherAlgorithm = sign(header, userClaims());
        assertThatThrownBy(() -> tokens.validate(otherAlgorithm, IdentityType.USER_ACCESS))
                .isInstanceOf(UnsupportedJwtException.class);
        header = header();
        header.put("kid", "unknown-key");
        String unknownKey = sign(header, userClaims());
        assertThatThrownBy(() -> tokens.validate(unknownKey, IdentityType.USER_ACCESS))
                .isInstanceOf(UnsupportedJwtException.class);
        String token = sign(header(), userClaims());
        String[] segments = token.split("\\.");
        byte[] signature = Base64.getUrlDecoder().decode(segments[2]);
        signature[0] ^= 1;
        String tampered = segments[0] + "." + segments[1] + "." + encode(signature);
        assertThatThrownBy(() -> tokens.validate(tampered, IdentityType.USER_ACCESS)).isInstanceOf(JwtException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"{invalid", "null", "[]", "{\"alg\":123}"})
    void malformedHeaderIsAnAuthenticationFailure(String rawHeader) {
        String token = encode(rawHeader.getBytes(StandardCharsets.UTF_8)) + ".e30.c2lnbmF0dXJl";
        assertThatThrownBy(() -> tokens.validate(token, IdentityType.USER_ACCESS))
                .isInstanceOf(MalformedJwtException.class);
    }

    @Test
    void clientAssertionKeepsItsOwnSubjectAudienceAndLifetime() throws Exception {
        var config = properties.getClientAssertion();
        var claims = userClaims();
        claims.remove("authorities");
        claims.remove("token_use");
        claims.remove("preferred_username");
        claims.put("iss", config.getIssuer());
        claims.put("aud", config.getAudience());
        claims.put("sub", config.getClientId());
        claims.put("iat", NOW.getEpochSecond());
        claims.put("nbf", NOW.getEpochSecond());
        claims.put("exp", NOW.plusSeconds(config.getMaxTtlSeconds()).getEpochSecond());
        var header = Map.<String, Object>of("alg", "RS256", "typ", config.getType(), "kid", "client-key");
        assertThat(tokens.validateClientAssertion(sign(header, claims)).clientId()).isEqualTo(config.getClientId());
        claims.put("scope", "order:write");
        String token = sign(header, claims);
        assertThatThrownBy(() -> tokens.validateClientAssertion(token)).isInstanceOf(MalformedJwtException.class);
    }

    private Map<String, Object> header() {
        return new LinkedHashMap<>(Map.of("alg", "RS256", "typ", properties.getUser().getType(),
                "kid", properties.getUser().getActiveKid()));
    }

    private Map<String, Object> userClaims() {
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", properties.getUser().getIssuer());
        claims.put("aud", properties.getUser().getAudience());
        claims.put("sub", Long.toString(USER_ID));
        claims.put("iat", NOW.minusSeconds(10).getEpochSecond());
        claims.put("nbf", NOW.minusSeconds(10).getEpochSecond());
        claims.put("exp", NOW.plusSeconds(300).getEpochSecond());
        claims.put("jti", "legacy-jti");
        claims.put("token_use", properties.getUser().getTokenUse());
        claims.put("preferred_username", "alice");
        claims.put("authorities", List.of("ROLE_USER"));
        return claims;
    }

    // Build the former wire contract directly with JCA so verification cannot pass
    // only because the new JJWT writer and reader make the same incompatible change.
    private static String sign(Map<String, Object> header, Map<String, Object> claims) throws Exception {
        String content = encode(JSON.writeValueAsBytes(header)) + "." + encode(JSON.writeValueAsBytes(claims));
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(content.getBytes(StandardCharsets.US_ASCII));
        return content + "." + encode(signature.sign());
    }

    private static boolean validSignature(String token) throws Exception {
        String[] segments = token.split("\\.");
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initVerify(keyPair.getPublic());
        signature.update((segments[0] + "." + segments[1]).getBytes(StandardCharsets.US_ASCII));
        return signature.verify(Base64.getUrlDecoder().decode(segments[2]));
    }

    private static JsonNode decode(String token, int segment) {
        return JSON.readTree(Base64.getUrlDecoder().decode(token.split("\\.")[segment]));
    }

    private static String encode(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static void writePem(String name, String type, byte[] bytes) throws Exception {
        Files.writeString(keys.resolve(name), "-----BEGIN " + type + "-----\n"
                + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                + "\n-----END " + type + "-----\n");
    }
}
