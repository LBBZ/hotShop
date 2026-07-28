package com.real.common.audit;

public record AuthenticationAuditState(
        String authenticationDomain,
        String usernameHash,
        String reason
) implements AuditStateSummary {
    public AuthenticationAuditState {
        if (authenticationDomain == null || authenticationDomain.isBlank()) {
            throw new IllegalArgumentException("Authentication domain is required");
        }
    }

    public static AuthenticationAuditState succeeded(String authenticationDomain) {
        return new AuthenticationAuditState(authenticationDomain, null, null);
    }

    public static AuthenticationAuditState denied(
            String authenticationDomain,
            String usernameHash,
            String reason
    ) {
        return new AuthenticationAuditState(authenticationDomain, usernameHash, reason);
    }
}
