package com.real.admin.agenttools;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class AdminAgentToolService {
    static final String STATISTICS_TOOL = "read_low_risk_statistics";
    static final String ANOMALIES_TOOL = "read_anomaly_summary";
    static final String CONFIGURATION_DRAFT_TOOL = "create_low_risk_configuration_draft";

    private static final String TOOL_RESOURCE = "AGENT_TOOL";
    private static final String CONFIGURATION_DRAFT_RESOURCE = "AGENT_CONFIGURATION_DRAFT";
    private static final Set<String> REQUEST_FIELDS = Set.of(
            "configurationKey",
            "proposedValue",
            "reason"
    );

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final AdminAgentToolAuditService auditService;
    private final Clock clock;

    @Autowired
    public AdminAgentToolService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AdminAgentToolAuditService auditService
    ) {
        this(jdbcTemplate, objectMapper, auditService, Clock.systemUTC());
    }

    AdminAgentToolService(
            JdbcTemplate jdbcTemplate,
            ObjectMapper objectMapper,
            AdminAgentToolAuditService auditService,
            Clock clock
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.auditService = auditService;
        this.clock = clock;
    }

    @Transactional
    public AdminAgentStatisticsResponse statistics(
            long administratorId,
            boolean hasUnexpectedParameters,
            HttpServletRequest request
    ) {
        rejectUnexpectedQueryParameters(
                administratorId,
                STATISTICS_TOOL,
                hasUnexpectedParameters,
                request
        );
        try {
            AdminAgentStatisticsResponse response = new AdminAgentStatisticsResponse(
                    clock.instant(),
                    count("SELECT COUNT(*) FROM catalog_product WHERE status='ACTIVE' AND deleted_at IS NULL"),
                    count("SELECT COALESCE(SUM(stock), 0) FROM catalog_product WHERE status='ACTIVE' AND deleted_at IS NULL"),
                    count("SELECT COUNT(*) FROM sales_order WHERE created_at >= UTC_TIMESTAMP(6) - INTERVAL 1 DAY"),
                    count("SELECT COUNT(*) FROM sales_order WHERE status='PAID' AND paid_at >= UTC_TIMESTAMP(6) - INTERVAL 1 DAY"),
                    count("SELECT COUNT(*) FROM sale_reservation WHERE status IN ('RESERVED', 'ORDER_CREATED')")
            );
            auditService.appendSuccess(
                    administratorId,
                    STATISTICS_TOOL,
                    TOOL_RESOURCE,
                    STATISTICS_TOOL,
                    "parameterCount=0",
                    request
            );
            return response;
        } catch (RuntimeException exception) {
            auditService.appendFailure(
                    administratorId,
                    STATISTICS_TOOL,
                    TOOL_RESOURCE,
                    STATISTICS_TOOL,
                    "parameterCount=0",
                    request
            );
            throw exception;
        }
    }

    @Transactional
    public AdminAgentAnomalySummaryResponse anomalies(
            long administratorId,
            boolean hasUnexpectedParameters,
            HttpServletRequest request
    ) {
        rejectUnexpectedQueryParameters(
                administratorId,
                ANOMALIES_TOOL,
                hasUnexpectedParameters,
                request
        );
        try {
            List<AdminAgentAnomalyCount> anomalies = List.of(
                    new AdminAgentAnomalyCount(
                            "FAILED_OUTBOX_EVENTS",
                            "HIGH",
                            count("SELECT COUNT(*) FROM outbox_event WHERE status='FAILED'")
                    ),
                    new AdminAgentAnomalyCount(
                            "EXPIRED_PENDING_ORDERS",
                            "HIGH",
                            count("SELECT COUNT(*) FROM sales_order WHERE status='PENDING' AND expires_at < UTC_TIMESTAMP(6)")
                    ),
                    new AdminAgentAnomalyCount(
                            "EXPIRED_ACTIVE_RESERVATIONS",
                            "MEDIUM",
                            count("SELECT COUNT(*) FROM sale_reservation WHERE status='RESERVED' AND expires_at < UTC_TIMESTAMP(6)")
                    ),
                    new AdminAgentAnomalyCount(
                            "ACTIVE_PRODUCTS_WITHOUT_STOCK",
                            "LOW",
                            count("SELECT COUNT(*) FROM catalog_product WHERE status='ACTIVE' AND stock=0 AND deleted_at IS NULL")
                    ),
                    new AdminAgentAnomalyCount(
                            "FAILED_PAYMENTS_LAST_24_HOURS",
                            "MEDIUM",
                            count("SELECT COUNT(*) FROM payment_order WHERE status='FAILED' AND updated_at >= UTC_TIMESTAMP(6) - INTERVAL 1 DAY")
                    )
            );
            AdminAgentAnomalySummaryResponse response = new AdminAgentAnomalySummaryResponse(
                    clock.instant(),
                    anomalies
            );
            auditService.appendSuccess(
                    administratorId,
                    ANOMALIES_TOOL,
                    TOOL_RESOURCE,
                    ANOMALIES_TOOL,
                    "parameterCount=0",
                    request
            );
            return response;
        } catch (RuntimeException exception) {
            auditService.appendFailure(
                    administratorId,
                    ANOMALIES_TOOL,
                    TOOL_RESOURCE,
                    ANOMALIES_TOOL,
                    "parameterCount=0",
                    request
            );
            throw exception;
        }
    }

    @Transactional
    public AgentConfigurationDraftResponse createConfigurationDraft(
            long administratorId,
            String rawBody,
            boolean hasUnexpectedParameters,
            HttpServletRequest request
    ) {
        if (hasUnexpectedParameters) {
            reject(
                    administratorId,
                    "UNEXPECTED_QUERY_PARAMETER",
                    "unparsed",
                    request,
                    "Query parameters are not allowed"
            );
        }
        ParsedConfigurationDraft parsed;
        try {
            parsed = parse(rawBody);
        } catch (ApiException exception) {
            auditService.appendRejected(
                    administratorId,
                    CONFIGURATION_DRAFT_TOOL,
                    CONFIGURATION_DRAFT_RESOURCE,
                    CONFIGURATION_DRAFT_TOOL,
                    "SCHEMA_REJECTED",
                    "unparsed",
                    request
            );
            throw exception;
        }

        String draftId = UUID.randomUUID().toString();
        Instant createdAt = clock.instant();
        String parameterSummary = "configurationKey=" + parsed.configurationKey()
                + ",valueKind=" + valueKind(parsed.proposedValue());
        try {
            int inserted = jdbcTemplate.update(
                    """
                    INSERT INTO agent_configuration_draft (
                        configuration_draft_id, administrator_id, configuration_key,
                        proposed_value, reason, risk_level, status, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, 'LOW', 'DRAFT', ?, ?)
                    """,
                    draftId,
                    administratorId,
                    parsed.configurationKey(),
                    json(parsed.proposedValue()),
                    parsed.reason(),
                    Timestamp.from(createdAt),
                    Timestamp.from(createdAt)
            );
            if (inserted != 1) {
                throw new IllegalStateException("Configuration draft was not persisted");
            }
            auditService.appendSuccess(
                    administratorId,
                    CONFIGURATION_DRAFT_TOOL,
                    CONFIGURATION_DRAFT_RESOURCE,
                    draftId,
                    parameterSummary,
                    request
            );
            return new AgentConfigurationDraftResponse(
                    draftId,
                    parsed.configurationKey(),
                    parsed.proposedValue(),
                    "LOW",
                    "DRAFT",
                    createdAt
            );
        } catch (RuntimeException exception) {
            auditService.appendFailure(
                    administratorId,
                    CONFIGURATION_DRAFT_TOOL,
                    CONFIGURATION_DRAFT_RESOURCE,
                    draftId,
                    parameterSummary,
                    request
            );
            throw exception;
        }
    }

    private long count(String sql) {
        Long value = jdbcTemplate.queryForObject(sql, Long.class);
        if (value == null || value < 0) {
            throw new IllegalStateException("Aggregate query did not return a valid count");
        }
        return value;
    }

    private void rejectUnexpectedQueryParameters(
            long administratorId,
            String toolName,
            boolean hasUnexpectedParameters,
            HttpServletRequest request
    ) {
        if (hasUnexpectedParameters) {
            auditService.appendRejected(
                    administratorId,
                    toolName,
                    TOOL_RESOURCE,
                    toolName,
                    "SCHEMA_REJECTED",
                    "unexpectedQueryParameters=true",
                    request
            );
            throw ApiException.badRequest(
                    "AGENT_TOOL_SCHEMA_INVALID",
                    "Query parameters are not allowed"
            );
        }
    }

    private void reject(
            long administratorId,
            String outcome,
            String parameterSummary,
            HttpServletRequest request,
            String detail
    ) {
        auditService.appendRejected(
                administratorId,
                CONFIGURATION_DRAFT_TOOL,
                CONFIGURATION_DRAFT_RESOURCE,
                CONFIGURATION_DRAFT_TOOL,
                outcome,
                parameterSummary,
                request
        );
        throw ApiException.badRequest("AGENT_TOOL_SCHEMA_INVALID", detail);
    }

    private ParsedConfigurationDraft parse(String rawBody) {
        if (rawBody == null || rawBody.isBlank() || rawBody.length() > 2_048) {
            throw schemaError("Request body must contain a small JSON object");
        }
        JsonNode root;
        try (JsonParser parser = objectMapper.getFactory().createParser(rawBody)) {
            parser.enable(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
            root = objectMapper.readTree(parser);
            if (parser.nextToken() != null) {
                throw schemaError("Request body must contain one JSON value");
            }
        } catch (IOException exception) {
            throw schemaError("Request body must be valid JSON");
        }
        if (root == null || !root.isObject() || root.size() != REQUEST_FIELDS.size()) {
            throw schemaError("Request body must contain exactly the allowed fields");
        }
        root.fieldNames().forEachRemaining(field -> {
            if (!REQUEST_FIELDS.contains(field)) {
                throw schemaError("Request body contains an extra field");
            }
        });

        JsonNode keyNode = root.get("configurationKey");
        JsonNode proposedValue = root.get("proposedValue");
        JsonNode reasonNode = root.get("reason");
        if (keyNode == null || !keyNode.isTextual()
                || proposedValue == null || proposedValue.isNull()
                || reasonNode == null || !reasonNode.isTextual()) {
            throw schemaError("Configuration key, proposed value, and reason have invalid types");
        }

        String configurationKey = keyNode.textValue();
        String reason = reasonNode.textValue().strip();
        if (reason.isEmpty() || reason.length() > 500) {
            throw schemaError("Reason must contain 1-500 characters");
        }
        JsonNode normalizedValue = switch (configurationKey) {
            case "AGENT_RESPONSE_STYLE" -> responseStyle(proposedValue);
            case "AGENT_SUMMARY_WINDOW" -> boundedInteger(proposedValue, 1, 24);
            case "AGENT_TOOL_RESULT_LIMIT" -> boundedInteger(proposedValue, 1, 100);
            default -> throw schemaError("Configuration key is not allowed");
        };
        return new ParsedConfigurationDraft(configurationKey, normalizedValue, reason);
    }

    private JsonNode responseStyle(JsonNode value) {
        if (!value.isTextual()
                || !Set.of("CONCISE", "BALANCED", "DETAILED").contains(value.textValue())) {
            throw schemaError("Response style is not allowed");
        }
        return value.deepCopy();
    }

    private JsonNode boundedInteger(JsonNode value, int minimum, int maximum) {
        if (!value.isIntegralNumber() || !value.canConvertToInt()) {
            throw schemaError("Configuration value must be an integer");
        }
        int integer = value.intValue();
        if (integer < minimum || integer > maximum) {
            throw schemaError("Configuration value is outside the allowed range");
        }
        return value.deepCopy();
    }

    private String valueKind(JsonNode value) {
        return value.isTextual() ? "STRING" : "INTEGER";
    }

    private String json(JsonNode value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize configuration draft value", exception);
        }
    }

    private ApiException schemaError(String detail) {
        return ApiException.badRequest("AGENT_TOOL_SCHEMA_INVALID", detail);
    }

    private record ParsedConfigurationDraft(
            String configurationKey,
            JsonNode proposedValue,
            String reason
    ) {
    }
}
