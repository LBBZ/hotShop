package com.real.security.audit;

import com.real.common.audit.AuditEvent;

public interface AuditLogWriter {
    void append(AuditEvent event);

    void appendFailure(AuditEvent event);
}
