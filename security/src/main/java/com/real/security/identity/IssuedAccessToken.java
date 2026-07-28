package com.real.security.identity;

import java.time.Instant;

public record IssuedAccessToken(String value, String jti, Instant expiresAt) {
}
