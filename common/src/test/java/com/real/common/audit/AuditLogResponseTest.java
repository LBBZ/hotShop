package com.real.common.audit;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AuditLogResponseTest {
    @Test
    void preservesExplicitNullSummaryFieldsInAnIndependentUnmodifiableCopy() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderType", "ORDINARY");
        summary.put("reservationNo", null);

        AuditLogResponse response = response(summary);
        summary.put("orderType", "CHANGED");
        summary.remove("reservationNo");
        summary.put("newField", "not part of the snapshot");

        assertThat(response.stateSummary())
                .hasSize(2)
                .containsEntry("orderType", "ORDINARY")
                .containsEntry("reservationNo", null);
        assertThatThrownBy(() -> response.stateSummary().put("reservationNo", "changed"))
                .isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> response.stateSummary().entrySet().iterator().next().setValue("changed"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void nullSummaryRetainsTheExistingEmptyObjectContract() {
        assertThat(response(null).stateSummary()).isEmpty();
    }

    @Test
    void responseSerializationStillUsesTheConfiguredMapContentInclusion() {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("orderType", "ORDINARY");
        summary.put("reservationNo", null);
        AuditLogResponse response = response(summary);
        JsonMapper mapper = JsonMapper.builder().build();

        var json = mapper.readTree(mapper.writeValueAsBytes(response));

        assertThat(json.path("stateSummary").has("reservationNo")).isTrue();
        assertThat(json.path("stateSummary").path("reservationNo").isNull()).isTrue();
        assertThat(json.path("stateSummary").path("orderType").stringValue()).isEqualTo("ORDINARY");
        assertThat(json.has("requestId")).isFalse();
    }

    private static AuditLogResponse response(Map<String, Object> summary) {
        return new AuditLogResponse(1L, AuditActorType.SYSTEM, null, null, null,
                "INVENTORY_COMPENSATED", "SALES_ORDER", "order-1", AuditResult.SUCCESS,
                null, null, AuditSource.TASK, Instant.parse("2026-10-01T00:00:00Z"), summary);
    }
}
