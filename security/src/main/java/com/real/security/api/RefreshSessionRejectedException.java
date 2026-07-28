package com.real.security.api;

import com.real.security.identity.SessionType;
import org.springframework.security.core.AuthenticationException;

public class RefreshSessionRejectedException extends AuthenticationException {
    private final SessionType sessionType;

    public RefreshSessionRejectedException(SessionType sessionType) {
        super("Refresh session is not valid");
        this.sessionType = sessionType;
    }

    public SessionType getSessionType() {
        return sessionType;
    }
}
