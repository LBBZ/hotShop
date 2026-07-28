package com.real.security.identity;

import java.time.Instant;

public record RefreshSessionTokens(
        String refreshToken,
        String csrfToken,
        String familyId,
        Instant expiresAt
) {
}
