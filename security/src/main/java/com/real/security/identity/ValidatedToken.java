package com.real.security.identity;

import java.time.Instant;
import java.util.Set;

public record ValidatedToken(
        IdentityType identityType,
        long subjectUserId,
        String username,
        String jti,
        Instant expiresAt,
        String authorizedParty,
        Set<String> scopes
) {
    public ValidatedToken {
        scopes = scopes == null ? Set.of() : Set.copyOf(scopes);
    }
}
