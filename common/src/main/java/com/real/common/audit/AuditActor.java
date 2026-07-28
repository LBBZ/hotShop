package com.real.common.audit;

import org.springframework.util.StringUtils;

public record AuditActor(AuditActorType type, String id) {
    public AuditActor {
        if (type == null) {
            throw new IllegalArgumentException("Audit actor type is required");
        }
        if (id != null && (!StringUtils.hasText(id) || id.length() > 128)) {
            throw new IllegalArgumentException("Audit actor ID must be 1-128 characters");
        }
    }

    public static AuditActor identified(AuditActorType type, Object id) {
        if (id == null) {
            throw new IllegalArgumentException("Identified audit actor requires an ID");
        }
        return new AuditActor(type, id.toString());
    }

    public static AuditActor system() {
        return new AuditActor(AuditActorType.SYSTEM, null);
    }
}
