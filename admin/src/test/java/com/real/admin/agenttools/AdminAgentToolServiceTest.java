package com.real.admin.agenttools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.ApiException;
import com.real.common.api.RequestContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletRequest;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class AdminAgentToolServiceTest {
    private static final Instant NOW = Instant.parse("2026-08-09T06:00:00Z");

    private JdbcTemplate jdbcTemplate;
    private AdminAgentToolAuditService auditService;
    private AdminAgentToolService service;
    private MockHttpServletRequest request;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        auditService = mock(AdminAgentToolAuditService.class);
        service = new AdminAgentToolService(
                jdbcTemplate,
                new ObjectMapper(),
                auditService,
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
        request = new MockHttpServletRequest();
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, "admin-agent-tool-request");
        request.setAttribute(RequestContext.TRACE_ID_ATTRIBUTE, "a".repeat(32));
    }

    @Test
    void statisticsReturnsOnlyFixedLowRiskAggregatesAndAudits() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(7L, 120L, 5L, 2L, 3L);

        AdminAgentStatisticsResponse result = service.statistics(41L, false, request);

        assertThat(result).isEqualTo(new AdminAgentStatisticsResponse(
                NOW,
                7L,
                120L,
                5L,
                2L,
                3L
        ));
        verify(auditService).appendSuccess(
                41L,
                AdminAgentToolService.STATISTICS_TOOL,
                "AGENT_TOOL",
                AdminAgentToolService.STATISTICS_TOOL,
                "parameterCount=0",
                request
        );
    }

    @Test
    void anomaliesReturnsCountsWithoutResourceIdentifiersOrRawErrors() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class)))
                .thenReturn(1L, 2L, 3L, 4L, 5L);

        AdminAgentAnomalySummaryResponse result = service.anomalies(42L, false, request);

        assertThat(result.generatedAt()).isEqualTo(NOW);
        assertThat(result.anomalies()).containsExactly(
                new AdminAgentAnomalyCount("FAILED_OUTBOX_EVENTS", "HIGH", 1L),
                new AdminAgentAnomalyCount("EXPIRED_PENDING_ORDERS", "HIGH", 2L),
                new AdminAgentAnomalyCount("EXPIRED_ACTIVE_RESERVATIONS", "MEDIUM", 3L),
                new AdminAgentAnomalyCount("ACTIVE_PRODUCTS_WITHOUT_STOCK", "LOW", 4L),
                new AdminAgentAnomalyCount("FAILED_PAYMENTS_LAST_24_HOURS", "MEDIUM", 5L)
        );
        verify(auditService).appendSuccess(
                42L,
                AdminAgentToolService.ANOMALIES_TOOL,
                "AGENT_TOOL",
                AdminAgentToolService.ANOMALIES_TOOL,
                "parameterCount=0",
                request
        );
    }

    @Test
    void configurationDraftPersistsButDoesNotApplyAllowedConfiguration() {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);
        String body = """
                {
                  "configurationKey": "AGENT_RESPONSE_STYLE",
                  "proposedValue": "BALANCED",
                  "reason": "Prefer a neutral response style"
                }
                """;

        AgentConfigurationDraftResponse result =
                service.createConfigurationDraft(43L, body, false, request);

        assertThat(result.configurationDraftId())
                .matches("^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(result.configurationKey()).isEqualTo("AGENT_RESPONSE_STYLE");
        assertThat(result.proposedValue().textValue()).isEqualTo("BALANCED");
        assertThat(result.riskLevel()).isEqualTo("LOW");
        assertThat(result.status()).isEqualTo("DRAFT");
        assertThat(result.createdAt()).isEqualTo(NOW);
        verify(jdbcTemplate).update(
                contains("INSERT INTO agent_configuration_draft"),
                eq(result.configurationDraftId()),
                eq(43L),
                eq("AGENT_RESPONSE_STYLE"),
                eq("\"BALANCED\""),
                eq("Prefer a neutral response style"),
                any(Timestamp.class),
                any(Timestamp.class)
        );
        verify(auditService).appendSuccess(
                eq(43L),
                eq(AdminAgentToolService.CONFIGURATION_DRAFT_TOOL),
                eq("AGENT_CONFIGURATION_DRAFT"),
                eq(result.configurationDraftId()),
                eq("configurationKey=AGENT_RESPONSE_STYLE,valueKind=STRING"),
                eq(request)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"configurationKey\":\"AGENT_SUMMARY_WINDOW\",\"proposedValue\":\"12\",\"reason\":\"test\"}",
            "{\"configurationKey\":\"AGENT_SUMMARY_WINDOW\",\"proposedValue\":25,\"reason\":\"test\"}",
            "{\"configurationKey\":\"UNSAFE_DYNAMIC_KEY\",\"proposedValue\":1,\"reason\":\"test\"}",
            "{\"configurationKey\":\"AGENT_RESPONSE_STYLE\",\"proposedValue\":\"BALANCED\",\"reason\":\"test\",\"url\":\"https://example.invalid\"}",
            "{\"configurationKey\":\"AGENT_RESPONSE_STYLE\",\"configurationKey\":\"AGENT_SUMMARY_WINDOW\",\"proposedValue\":12,\"reason\":\"test\"}",
            "{\"configurationKey\":\"AGENT_TOOL_RESULT_LIMIT\",\"proposedValue\":1.0,\"reason\":\"test\"}",
            "{\"configurationKey\":\"AGENT_TOOL_RESULT_LIMIT\",\"proposedValue\":10,\"reason\":\"test\"} {}",
            "[]",
            "not-json"
    })
    void configurationDraftRejectsDynamicKeysExtraFieldsTypeConfusionAndBadRanges(String body) {
        assertThatThrownBy(() -> service.createConfigurationDraft(44L, body, false, request))
                .isInstanceOfSatisfying(ApiException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("AGENT_TOOL_SCHEMA_INVALID");
                    assertThat(exception.getStatus().value()).isEqualTo(400);
                });

        verifyNoInteractions(jdbcTemplate);
        verify(auditService).appendRejected(
                44L,
                AdminAgentToolService.CONFIGURATION_DRAFT_TOOL,
                "AGENT_CONFIGURATION_DRAFT",
                AdminAgentToolService.CONFIGURATION_DRAFT_TOOL,
                "SCHEMA_REJECTED",
                "unparsed",
                request
        );
    }

    @Test
    void readToolsRejectAllQueryParametersBeforeReadingFacts() {
        assertThatThrownBy(() -> service.statistics(45L, true, request))
                .isInstanceOfSatisfying(ApiException.class, exception ->
                        assertThat(exception.getCode()).isEqualTo("AGENT_TOOL_SCHEMA_INVALID"));

        verifyNoInteractions(jdbcTemplate);
        verify(auditService).appendRejected(
                45L,
                AdminAgentToolService.STATISTICS_TOOL,
                "AGENT_TOOL",
                AdminAgentToolService.STATISTICS_TOOL,
                "SCHEMA_REJECTED",
                "unexpectedQueryParameters=true",
                request
        );
        verify(auditService, never()).appendSuccess(
                anyLong(),
                anyString(),
                anyString(),
                anyString(),
                anyString(),
                any()
        );
    }
}
