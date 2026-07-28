package com.real.common.audit;

import org.springframework.util.StringUtils;

public record AuditResource(AuditResourceType type, String id) {
    public AuditResource {
        if (type == null) {
            throw new IllegalArgumentException("Audit resource type is required");
        }
        if (id != null && (!StringUtils.hasText(id) || id.length() > 128)) {
            throw new IllegalArgumentException("Audit resource ID must be 1-128 characters");
        }
    }

    public static AuditResource identified(AuditResourceType type, Object id) {
        if (id == null) {
            throw new IllegalArgumentException("Identified audit resource requires an ID");
        }
        return new AuditResource(type, id.toString());
    }
}
