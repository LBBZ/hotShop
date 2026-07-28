package com.real.security.audit;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.real.common.audit.AuthenticationAuditState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AuditSensitiveDataSanitizerTest {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AuditSensitiveDataSanitizer sanitizer =
            new AuditSensitiveDataSanitizer(objectMapper);

    @Test
    void sensitiveKeysAndCredentialShapedValuesAreRedacted() {
        ObjectNode unsafe = objectMapper.createObjectNode();
        unsafe.put("password", "Password-Secret");
        unsafe.put("accessToken", "Access-Secret");
        unsafe.put("refresh_token", "Refresh-Secret");
        unsafe.put("apiKey", "Api-Key-Secret");
        unsafe.put("Cookie", "session=Cookie-Secret");
        unsafe.put("Authorization", "Bearer Authorization-Secret");
        unsafe.put("clientAssertion", "Client-Assertion-Secret");
        unsafe.put("fullPrompt", "Complete-Prompt-Secret");
        unsafe.put("chainOfThought", "Private-Reasoning-Secret");
        unsafe.put("完整提示词", "Chinese-Prompt-Secret");
        unsafe.put("思维链", "Chinese-Reasoning-Secret");
        unsafe.put("note", "Bearer Value-Secret");
        unsafe.put("jwt", "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxIn0.signature");

        JsonNode sanitized = sanitizer.sanitizeNode(unsafe);
        String json = sanitized.toString();

        for (String secret : List.of(
                "Password-Secret",
                "Access-Secret",
                "Refresh-Secret",
                "Api-Key-Secret",
                "Cookie-Secret",
                "Authorization-Secret",
                "Client-Assertion-Secret",
                "Complete-Prompt-Secret",
                "Private-Reasoning-Secret",
                "Chinese-Prompt-Secret",
                "Chinese-Reasoning-Secret",
                "Value-Secret",
                "eyJhbGciOiJSUzI1NiJ9"
        )) {
            assertThat(json).doesNotContain(secret);
        }
        assertThat(json).contains(AuditSensitiveDataSanitizer.REDACTED);
    }

    @Test
    void typedSummaryKeepsAllowlistedEvidenceAndOmitsNulls() {
        JsonNode sanitized = sanitizer.sanitize(
                AuthenticationAuditState.succeeded("ADMIN")
        );

        assertThat(sanitized.get("authenticationDomain").asText()).isEqualTo("ADMIN");
        assertThat(sanitized.has("usernameHash")).isFalse();
        assertThat(sanitized.has("reason")).isFalse();
    }
}
