package com.real.security.identity;

import java.time.Instant;

public record ValidatedClientAssertion(String clientId, String jti, Instant expiresAt) {
}
