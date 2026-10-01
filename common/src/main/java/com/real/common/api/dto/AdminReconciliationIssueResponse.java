package com.real.common.api.dto;

import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.ser.std.ToStringSerializer;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.Map;

public record AdminReconciliationIssueResponse(
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string") Long issueId,
        String issueType,
        String severity,
        String status,
        @JsonSerialize(using = ToStringSerializer.class) @Schema(type = "string") Long activityId,
        String reservationNo,
        int occurrences,
        int evidenceVersion,
        Map<String, Object> evidenceSummary,
        Instant firstSeenAt,
        Instant lastSeenAt
) {
}
