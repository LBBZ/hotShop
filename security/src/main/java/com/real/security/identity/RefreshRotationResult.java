package com.real.security.identity;

public record RefreshRotationResult(
        Status status,
        long userId,
        String username,
        RefreshSessionTokens tokens
) {
    public enum Status {
        ROTATED,
        INVALID,
        REUSED
    }

    public static RefreshRotationResult rotated(
            long userId,
            String username,
            RefreshSessionTokens tokens
    ) {
        return new RefreshRotationResult(Status.ROTATED, userId, username, tokens);
    }

    public static RefreshRotationResult invalid() {
        return new RefreshRotationResult(Status.INVALID, 0, null, null);
    }

    public static RefreshRotationResult reused() {
        return new RefreshRotationResult(Status.REUSED, 0, null, null);
    }
}
