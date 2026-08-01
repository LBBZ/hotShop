package com.real.task.seckill;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Service
public class SeckillProcessingService {
    private static final String ORDER_CREATED = "ORDER_CREATED";
    private static final String COMPENSATING = "COMPENSATING";
    private static final String COMPENSATED = "COMPENSATED";
    private static final String RETRYING = "RETRYING";
    private static final String MANUAL_REVIEW = "MANUAL_REVIEW";
    private static final String QUARANTINED = "QUARANTINED";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final SeckillOrderProperties properties;
    private final SeckillProcessingFailpoint failpoint;
    private final Clock clock;

    public SeckillProcessingService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SeckillOrderProperties properties,
            SeckillProcessingFailpoint failpoint
    ) {
        this(jdbc, objectMapper, properties, failpoint, Clock.systemUTC());
    }

    SeckillProcessingService(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            SeckillOrderProperties properties,
            SeckillProcessingFailpoint failpoint,
            Clock clock
    ) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.failpoint = failpoint;
        this.clock = clock;
    }

    @Transactional
    public ProcessOutcome createOrder(
            String streamKey,
            String streamEntryId,
            ReservationAcceptedEvent event
    ) {
        ProcessingRow processing = claim(streamKey, streamEntryId, event);
        ProcessOutcome collision = collisionOutcome(processing, streamKey, streamEntryId, event);
        if (collision != null) {
            return collision;
        }
        if (ORDER_CREATED.equals(processing.status())) {
            String orderId = verifyCommittedOrder(event);
            return ProcessOutcome.duplicate(orderId);
        }
        if (COMPENSATING.equals(processing.status())) {
            ensureNoOrderBeforeCompensation(event);
            return ProcessOutcome.compensating(
                    processing.compensationId(),
                    processing.reasonCode()
            );
        }
        if (COMPENSATED.equals(processing.status())) {
            ensureNoOrderBeforeCompensation(event);
            return ProcessOutcome.compensated(
                    processing.compensationId(),
                    processing.reasonCode()
            );
        }
        if (MANUAL_REVIEW.equals(processing.status()) || QUARANTINED.equals(processing.status())) {
            return ProcessOutcome.manual(processing.reasonCode());
        }
        if (processing.nextAttemptAt() != null
                && processing.nextAttemptAt().isAfter(clock.instant())) {
            return ProcessOutcome.retryNotDue();
        }

        ReservationRow reservation = insertOrLockReservation(event, "RESERVED");
        if (!immutableFactsMatch(reservation, event)) {
            markManualInCurrentTransaction(
                    processing.processingId(),
                    "RESERVATION_IMMUTABLE_CONFLICT",
                    event,
                    streamKey,
                    streamEntryId
            );
            return ProcessOutcome.manual("RESERVATION_IMMUTABLE_CONFLICT");
        }
        if (ORDER_CREATED.equals(reservation.status())) {
            String orderId = verifyCommittedOrder(event);
            recordProcessedEvent(event);
            markOrderCreated(processing.processingId(), event, orderId);
            return ProcessOutcome.duplicate(orderId);
        }
        if (COMPENSATING.equals(reservation.status()) || COMPENSATED.equals(reservation.status())) {
            markManualInCurrentTransaction(
                    processing.processingId(),
                    "RESERVATION_TERMINAL_CONFLICT",
                    event,
                    streamKey,
                    streamEntryId
            );
            return ProcessOutcome.manual("RESERVATION_TERMINAL_CONFLICT");
        }
        if (!"RESERVED".equals(reservation.status())) {
            markManualInCurrentTransaction(
                    processing.processingId(),
                    "RESERVATION_STATUS_INVALID",
                    event,
                    streamKey,
                    streamEntryId
            );
            return ProcessOutcome.manual("RESERVATION_STATUS_INVALID");
        }

        deductActivityStock(event);
        deductCatalogStock(event);

        String orderId = event.deterministicOrderId();
        BigDecimal amount = event.totalAmount();
        try {
            jdbc.update("""
                    INSERT INTO sales_order (
                        order_id, user_id, reservation_id, total_amount, currency,
                        status, expires_at
                    ) VALUES (?, ?, ?, ?, 'CNY', 'PENDING', ?)
                    """,
                    orderId,
                    event.userId(),
                    reservation.reservationId(),
                    amount,
                    Timestamp.from(clock.instant().plus(properties.getPaymentTimeout()))
            );
        } catch (DuplicateKeyException exception) {
            throw new ManualFactFailure("ORDER_ID_OR_RESERVATION_CONFLICT");
        }
        jdbc.update("""
                INSERT INTO sales_order_item (
                    order_id, product_id, quantity, price, line_amount
                ) VALUES (?, ?, ?, ?, ?)
                """,
                orderId,
                event.productId(),
                event.quantity(),
                event.unitPrice(),
                amount
        );
        int reservationUpdated = jdbc.update("""
                UPDATE sale_reservation
                   SET status = 'ORDER_CREATED',
                       order_id = ?,
                       version = version + 1
                 WHERE reservation_id = ?
                   AND status = 'RESERVED'
                   AND order_id IS NULL
                """, orderId, reservation.reservationId());
        if (reservationUpdated != 1) {
            throw new ManualFactFailure("RESERVATION_TRANSITION_CONFLICT");
        }

        recordProcessedEvent(event);
        insertOutbox(
                event.deterministicOrderCreatedOutboxId(),
                "ORDER",
                orderId,
                ORDER_CREATED,
                Map.ofEntries(
                        Map.entry("schemaVersion", 1),
                        Map.entry("eventType", ORDER_CREATED),
                        Map.entry("orderId", orderId),
                        Map.entry("reservationNo", event.reservationNo()),
                        Map.entry("activityId", event.activityId()),
                        Map.entry("productId", event.productId()),
                        Map.entry("quantity", event.quantity()),
                        Map.entry("unitPrice", event.unitPrice()),
                        Map.entry("totalAmount", amount),
                        Map.entry("currency", event.currency()),
                        Map.entry("occurredAtMs", clock.millis())
                )
        );
        markOrderCreated(processing.processingId(), event, orderId);
        failpoint.beforeOrderCommit(event);
        return ProcessOutcome.created(orderId);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public FailureOutcome recordFailure(
            String streamKey,
            String streamEntryId,
            ReservationAcceptedEvent event,
            FailureKind kind,
            String reasonCode
    ) {
        ProcessingRow processing = claim(streamKey, streamEntryId, event);
        ProcessOutcome collision = collisionOutcome(processing, streamKey, streamEntryId, event);
        if (collision != null) {
            return FailureOutcome.manual(collision.reasonCode());
        }
        if (ORDER_CREATED.equals(processing.status())) {
            return FailureOutcome.orderCreated(verifyCommittedOrder(event));
        }
        if (COMPENSATED.equals(processing.status())) {
            try {
                validateCompensatedFacts(
                        event,
                        processing,
                        processing.compensationId(),
                        processing.reasonCode()
                );
                return FailureOutcome.compensated(
                        processing.compensationId(),
                        processing.reasonCode()
                );
            } catch (ManualFactFailure conflict) {
                String conflictReason = conflict.getMessage();
                markManualInCurrentTransaction(
                        processing.processingId(),
                        conflictReason,
                        event,
                        streamKey,
                        streamEntryId
                );
                return FailureOutcome.manual(conflictReason);
            }
        }
        if (COMPENSATING.equals(processing.status())) {
            ensureNoOrderBeforeCompensation(event);
            return FailureOutcome.compensating(
                    processing.compensationId(),
                    processing.reasonCode()
            );
        }
        if (kind == FailureKind.MANUAL) {
            markManualInCurrentTransaction(
                    processing.processingId(),
                    reasonCode,
                    event,
                    streamKey,
                    streamEntryId
            );
            return FailureOutcome.manual(reasonCode);
        }

        int attempts = processing.attempts() + 1;
        if (kind == FailureKind.SAFE_INVENTORY
                && attempts >= properties.getDeterministicFailureAttempts()) {
            return prepareCompensation(
                    processing,
                    streamKey,
                    streamEntryId,
                    event,
                    attempts,
                    reasonCode
            );
        }
        Duration backoff = backoff(attempts);
        jdbc.update("""
                UPDATE seckill_event_processing
                   SET status = 'RETRYING',
                       attempts = ?,
                       next_attempt_at = ?,
                       reason_code = ?,
                       last_error = ?,
                       version = version + 1
                 WHERE processing_id = ?
                """,
                attempts,
                Timestamp.from(clock.instant().plus(backoff)),
                reasonCode,
                sanitizedError(kind, reasonCode),
                processing.processingId()
        );
        return FailureOutcome.retrying(attempts);
    }

    @Transactional
    public void quarantineInvalid(
            String streamKey,
            String streamEntryId,
            ReservationAcceptedEvent.ParseResult invalid
    ) {
        jdbc.update("""
                INSERT INTO seckill_event_processing (
                    event_id, stream_key, stream_entry_id, payload_hash,
                    status, attempts, reason_code, last_error
                ) VALUES (?, ?, ?, ?, 'QUARANTINED', 1, ?, ?)
                ON DUPLICATE KEY UPDATE
                    status = CASE
                        WHEN status IN ('ORDER_CREATED', 'COMPENSATED') THEN status
                        ELSE 'QUARANTINED'
                    END,
                    reason_code = VALUES(reason_code),
                    last_error = VALUES(last_error),
                    attempts = attempts + 1,
                    version = version + 1
                """,
                invalid.safeEventId(),
                streamKey,
                streamEntryId,
                invalid.payloadHash(),
                firstReason(invalid.errors()),
                "POISON_EVENT:" + firstReason(invalid.errors())
        );
        recordIssue(
                "POISON_STREAM_EVENT",
                "CRITICAL",
                null,
                null,
                streamKey,
                streamEntryId,
                invalid.payloadHash(),
                Map.of(
                        "schemaVersion", 1,
                        "classification", "POISON_EVENT",
                        "validationCodes", invalid.errors(),
                        "payloadHash", invalid.payloadHash()
                )
        );
    }

    @Transactional
    public void finishCompensation(
            ReservationAcceptedEvent event,
            String compensationId,
            String reasonCode
    ) {
        List<ProcessingRow> rows = rowsForEvent(event.eventId(), true);
        if (rows.size() != 1) {
            throw new IllegalStateException("Compensation processing record is unavailable");
        }
        ProcessingRow processing = rows.get(0);
        if (!event.payloadHash().equals(processing.payloadHash())) {
            throw new ManualFactFailure("EVENT_PAYLOAD_CONFLICT");
        }
        if (COMPENSATED.equals(processing.status())) {
            validateCompensatedFacts(event, processing, compensationId, reasonCode);
            return;
        }
        if (!COMPENSATING.equals(processing.status())
                || !Objects.equals(processing.compensationId(), compensationId)
                || !Objects.equals(processing.reasonCode(), reasonCode)) {
            throw new ManualFactFailure("COMPENSATION_INTENT_CONFLICT");
        }
        validateCompensationReservation(event);
        ensureNoOrderBeforeCompensation(event);
        failpoint.beforeCompensationCommit(event);
        int reservationUpdated = jdbc.update("""
                UPDATE sale_reservation
                   SET status = 'COMPENSATED',
                       version = version + 1
                 WHERE reservation_no = ?
                   AND status = 'COMPENSATING'
                   AND order_id IS NULL
                """, event.reservationNo());
        if (reservationUpdated == 0) {
            Integer already = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM sale_reservation
                     WHERE reservation_no = ?
                       AND status = 'COMPENSATED'
                       AND order_id IS NULL
                    """, Integer.class, event.reservationNo());
            if (already == null || already != 1) {
                throw new ManualFactFailure("MYSQL_COMPENSATION_STATE_CONFLICT");
            }
        }
        jdbc.update("""
                UPDATE seckill_event_processing
                   SET status = 'COMPENSATED',
                       compensation_id = ?,
                       reason_code = ?,
                       next_attempt_at = NULL,
                       last_error = NULL,
                       version = version + 1
                 WHERE processing_id = ?
                """, compensationId, reasonCode, processing.processingId());
        recordProcessedEvent(event);
        insertOutbox(
                event.deterministicCompensationOutboxId(),
                "RESERVATION",
                event.reservationNo(),
                "RESERVATION_COMPENSATED",
                Map.of(
                        "schemaVersion", 1,
                        "eventType", "RESERVATION_COMPENSATED",
                        "reservationNo", event.reservationNo(),
                        "activityId", event.activityId(),
                        "productId", event.productId(),
                        "quantity", event.quantity(),
                        "currency", event.currency(),
                        "compensationId", compensationId,
                        "reasonCode", reasonCode,
                        "occurredAtMs", event.occurredAtMs()
                )
        );
        jdbc.update("""
                INSERT INTO audit_log (
                    actor_type, actor_id, action, resource_type, resource_id,
                    result, source, state_summary
                ) VALUES (
                    'SYSTEM', 'seckill-order-consumer',
                    'RESERVATION_COMPENSATED', 'FLASH_SALE_RESERVATION', ?,
                    'SUCCESS', 'TASK', CAST(? AS JSON)
                )
                """,
                event.reservationNo(),
                json(Map.of(
                        "schemaVersion", 1,
                        "status", "COMPENSATED",
                        "reasonCode", reasonCode,
                        "compensationId", compensationId
                ))
        );
    }

    @Transactional
    public String validateCommittedOrderForRepair(ReservationAcceptedEvent event) {
        return verifyCommittedOrder(event);
    }

    @Transactional
    public CompensationRepairIntent validateCompensationForRepair(
            ReservationAcceptedEvent event
    ) {
        List<ProcessingRow> rows = rowsForEvent(event.eventId(), true);
        if (rows.size() != 1) {
            throw new ManualFactFailure("COMPENSATION_INTENT_MISSING");
        }
        ProcessingRow processing = rows.get(0);
        if (!event.payloadHash().equals(processing.payloadHash())
                || !Set.of(COMPENSATING, COMPENSATED).contains(processing.status())
                || processing.compensationId() == null
                || processing.reasonCode() == null) {
            throw new ManualFactFailure("COMPENSATION_INTENT_CONFLICT");
        }
        validateCompensationReservation(event);
        ensureNoOrderBeforeCompensation(event);
        return new CompensationRepairIntent(
                processing.compensationId(),
                processing.reasonCode(),
                COMPENSATED.equals(processing.status())
        );
    }

    @Transactional
    public void markManual(
            String streamKey,
            String streamEntryId,
            ReservationAcceptedEvent event,
            String reasonCode
    ) {
        ProcessingRow processing = claim(streamKey, streamEntryId, event);
        markManualInCurrentTransaction(
                processing.processingId(),
                reasonCode,
                event,
                streamKey,
                streamEntryId
        );
    }

    public boolean retryDue(String eventId) {
        List<ProcessingRow> rows = rowsForEvent(eventId, false);
        return rows.isEmpty()
                || rows.get(0).nextAttemptAt() == null
                || !rows.get(0).nextAttemptAt().isAfter(clock.instant());
    }

    @Transactional
    public ProcessOutcome knownTerminalOutcome(ReservationAcceptedEvent event) {
        List<ProcessingRow> rows = rowsForEvent(event.eventId(), true);
        if (rows.size() != 1 || !event.payloadHash().equals(rows.get(0).payloadHash())) {
            return null;
        }
        ProcessingRow processing = rows.get(0);
        return switch (processing.status()) {
            case ORDER_CREATED -> ProcessOutcome.duplicate(verifyCommittedOrder(event));
            case COMPENSATING -> {
                ensureNoOrderBeforeCompensation(event);
                yield ProcessOutcome.compensating(
                        processing.compensationId(),
                        processing.reasonCode()
                );
            }
            case COMPENSATED -> {
                ensureNoOrderBeforeCompensation(event);
                yield ProcessOutcome.compensated(
                        processing.compensationId(),
                        processing.reasonCode()
                );
            }
            case MANUAL_REVIEW, QUARANTINED ->
                    ProcessOutcome.manual(processing.reasonCode());
            default -> null;
        };
    }

    @Transactional
    public ProcessOutcome mergeCommittedOrder(
            String streamKey,
            String streamEntryId,
            ReservationAcceptedEvent event
    ) {
        ProcessingRow processing = claim(streamKey, streamEntryId, event);
        ProcessOutcome collision = collisionOutcome(
                processing,
                streamKey,
                streamEntryId,
                event
        );
        if (collision != null) {
            return collision;
        }
        if (COMPENSATING.equals(processing.status())
                || COMPENSATED.equals(processing.status())) {
            markManualInCurrentTransaction(
                    processing.processingId(),
                    "ORDER_AND_COMPENSATION_CONFLICT",
                    event,
                    streamKey,
                    streamEntryId
            );
            return ProcessOutcome.manual("ORDER_AND_COMPENSATION_CONFLICT");
        }
        String orderId = verifyCommittedOrder(event);
        if (!ORDER_CREATED.equals(processing.status())) {
            recordProcessedEvent(event);
            markOrderCreated(processing.processingId(), event, orderId);
        }
        return ProcessOutcome.duplicate(orderId);
    }

    @Transactional
    public void recordReconciliationIssue(
            String issueType,
            String severity,
            Long activityId,
            String reservationNo,
            String streamKey,
            String streamEntryId,
            Map<String, ?> evidence
    ) {
        recordIssue(
                issueType,
                severity,
                activityId,
                reservationNo,
                streamKey,
                streamEntryId,
                "",
                evidence
        );
    }

    private FailureOutcome prepareCompensation(
            ProcessingRow processing,
            String streamKey,
            String streamEntryId,
            ReservationAcceptedEvent event,
            int attempts,
            String reasonCode
    ) {
        ReservationRow reservation = insertOrLockReservation(event, "COMPENSATING");
        if (!immutableFactsMatch(reservation, event)) {
            markManualInCurrentTransaction(
                    processing.processingId(),
                    "RESERVATION_IMMUTABLE_CONFLICT",
                    event,
                    streamKey,
                    streamEntryId
            );
            return FailureOutcome.manual("RESERVATION_IMMUTABLE_CONFLICT");
        }
        if (ORDER_CREATED.equals(reservation.status()) || reservation.orderId() != null) {
            return FailureOutcome.orderCreated(verifyCommittedOrder(event));
        }
        ensureNoOrderBeforeCompensation(event);
        if ("RESERVED".equals(reservation.status())) {
            int updated = jdbc.update("""
                    UPDATE sale_reservation
                       SET status = 'COMPENSATING',
                           version = version + 1
                     WHERE reservation_id = ?
                       AND status = 'RESERVED'
                       AND order_id IS NULL
                    """, reservation.reservationId());
            if (updated != 1) {
                throw new ManualFactFailure("COMPENSATION_INTENT_CONFLICT");
            }
        } else if (!COMPENSATING.equals(reservation.status())) {
            markManualInCurrentTransaction(
                    processing.processingId(),
                    "COMPENSATION_INTENT_CONFLICT",
                    event,
                    streamKey,
                    streamEntryId
            );
            return FailureOutcome.manual("COMPENSATION_INTENT_CONFLICT");
        }
        String compensationId = event.deterministicCompensationId();
        jdbc.update("""
                UPDATE seckill_event_processing
                   SET status = 'COMPENSATING',
                       attempts = ?,
                       next_attempt_at = NULL,
                       compensation_id = ?,
                       reason_code = ?,
                       last_error = NULL,
                       version = version + 1
                 WHERE processing_id = ?
                """, attempts, compensationId, reasonCode, processing.processingId());
        return FailureOutcome.compensating(compensationId, reasonCode);
    }

    private ProcessingRow claim(
            String streamKey,
            String streamEntryId,
            ReservationAcceptedEvent event
    ) {
        jdbc.update("""
                INSERT INTO seckill_event_processing (
                    event_id, stream_key, stream_entry_id, reservation_no,
                    activity_id, user_id, payload_hash, status
                ) VALUES (?, ?, ?, ?, ?, ?, ?, 'RETRYING')
                ON DUPLICATE KEY UPDATE processing_id = LAST_INSERT_ID(processing_id)
                """,
                event.eventId(),
                streamKey,
                streamEntryId,
                event.reservationNo(),
                event.activityId(),
                event.userId(),
                event.payloadHash()
        );
        List<ProcessingRow> rows = jdbc.query("""
                SELECT processing_id, event_id, stream_key, stream_entry_id,
                       reservation_no, activity_id, user_id, payload_hash,
                       status, attempts, next_attempt_at, order_id,
                       compensation_id, reason_code
                  FROM seckill_event_processing
                 WHERE event_id = ?
                    OR (stream_key = ? AND stream_entry_id = ?)
                 FOR UPDATE
                """, (resultSet, rowNum) -> processingRow(resultSet), event.eventId(), streamKey, streamEntryId);
        if (rows.size() != 1) {
            for (ProcessingRow row : rows) {
                jdbc.update("""
                        UPDATE seckill_event_processing
                           SET status = CASE
                                   WHEN status IN ('ORDER_CREATED', 'COMPENSATED') THEN status
                                   ELSE 'MANUAL_REVIEW'
                               END,
                               reason_code = 'EVENT_ID_STREAM_ID_COLLISION',
                               version = version + 1
                         WHERE processing_id = ?
                        """, row.processingId());
            }
            recordIssue(
                    "EVENT_ID_STREAM_ID_COLLISION",
                    "CRITICAL",
                    event.activityId(),
                    event.reservationNo(),
                    streamKey,
                    streamEntryId,
                    event.payloadHash(),
                    Map.of(
                            "schemaVersion", 1,
                            "classification", "IDENTITY_COLLISION",
                            "payloadHash", event.payloadHash()
                    )
            );
            throw new ManualFactFailure("EVENT_ID_STREAM_ID_COLLISION");
        }
        return rows.get(0);
    }

    private ProcessOutcome collisionOutcome(
            ProcessingRow processing,
            String streamKey,
            String streamEntryId,
            ReservationAcceptedEvent event
    ) {
        if (!event.payloadHash().equals(processing.payloadHash())) {
            recordIssue(
                    "EVENT_PAYLOAD_CONFLICT",
                    "CRITICAL",
                    event.activityId(),
                    event.reservationNo(),
                    streamKey,
                    streamEntryId,
                    event.payloadHash(),
                    Map.of(
                            "schemaVersion", 1,
                            "classification", "PAYLOAD_HASH_CONFLICT",
                            "incomingPayloadHash", event.payloadHash(),
                            "storedPayloadHash", processing.payloadHash()
                    )
            );
            if (!ORDER_CREATED.equals(processing.status())
                    && !COMPENSATED.equals(processing.status())) {
                jdbc.update("""
                        UPDATE seckill_event_processing
                           SET status = 'QUARANTINED',
                               reason_code = 'EVENT_PAYLOAD_CONFLICT',
                               last_error = 'POISON_EVENT:EVENT_PAYLOAD_CONFLICT',
                               version = version + 1
                         WHERE processing_id = ?
                        """, processing.processingId());
            }
            return ProcessOutcome.manual("EVENT_PAYLOAD_CONFLICT");
        }
        if (!processing.eventId().equals(event.eventId())) {
            recordIssue(
                    "STREAM_ENTRY_IDENTITY_CONFLICT",
                    "CRITICAL",
                    event.activityId(),
                    event.reservationNo(),
                    streamKey,
                    streamEntryId,
                    event.payloadHash(),
                    Map.of(
                            "schemaVersion", 1,
                            "classification", "STREAM_ENTRY_IDENTITY_CONFLICT",
                            "payloadHash", event.payloadHash()
                    )
            );
            return ProcessOutcome.manual("STREAM_ENTRY_IDENTITY_CONFLICT");
        }
        return null;
    }

    private ReservationRow insertOrLockReservation(
            ReservationAcceptedEvent event,
            String initialStatus
    ) {
        try {
            jdbc.update("""
                    INSERT INTO sale_reservation (
                        reservation_no, activity_id, user_id, product_id,
                        quantity, unit_price, reserved_amount, currency,
                        activity_version, idempotency_key_hash, request_fingerprint,
                        reserved_at, status, expires_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'CNY', ?, ?, ?, ?, ?, ?)
                    """,
                    event.reservationNo(),
                    event.activityId(),
                    event.userId(),
                    event.productId(),
                    event.quantity(),
                    event.unitPrice(),
                    event.totalAmount(),
                    event.activityVersion(),
                    event.idempotencyKeyHash(),
                    event.requestFingerprint(),
                    Timestamp.from(Instant.ofEpochMilli(event.occurredAtMs())),
                    initialStatus,
                    Timestamp.from(clock.instant().plus(properties.getOrderTimeout()))
            );
        } catch (DuplicateKeyException ignored) {
            // The unique Reservation or effective User slot is resolved under a row lock below.
        }
        List<ReservationRow> reservations = lockReservation(event.reservationNo());
        if (reservations.size() != 1) {
            throw new ManualFactFailure("EFFECTIVE_USER_SLOT_CONFLICT");
        }
        return reservations.get(0);
    }

    private List<ReservationRow> lockReservation(String reservationNo) {
        return jdbc.query("""
                SELECT reservation_id, reservation_no, activity_id, user_id,
                       product_id, quantity, unit_price, reserved_amount,
                       currency, activity_version, idempotency_key_hash,
                       request_fingerprint, status, order_id
                  FROM sale_reservation
                 WHERE reservation_no = ?
                 FOR UPDATE
                """, (resultSet, rowNum) -> new ReservationRow(
                resultSet.getLong("reservation_id"),
                resultSet.getString("reservation_no"),
                resultSet.getLong("activity_id"),
                resultSet.getLong("user_id"),
                resultSet.getLong("product_id"),
                resultSet.getInt("quantity"),
                resultSet.getBigDecimal("unit_price"),
                resultSet.getBigDecimal("reserved_amount"),
                resultSet.getString("currency"),
                (Integer) resultSet.getObject("activity_version"),
                resultSet.getString("idempotency_key_hash"),
                resultSet.getString("request_fingerprint"),
                resultSet.getString("status"),
                resultSet.getString("order_id")
        ), reservationNo);
    }

    private boolean immutableFactsMatch(ReservationRow row, ReservationAcceptedEvent event) {
        return row.reservationNo().equals(event.reservationNo())
                && row.activityId() == event.activityId()
                && row.userId() == event.userId()
                && row.productId() == event.productId()
                && row.quantity() == event.quantity()
                && equalMoney(row.unitPrice(), event.unitPrice())
                && equalMoney(row.reservedAmount(), event.totalAmount())
                && "CNY".equals(row.currency())
                && Objects.equals(row.activityVersion(), event.activityVersion())
                && event.idempotencyKeyHash().equals(row.idempotencyKeyHash())
                && event.requestFingerprint().equals(row.requestFingerprint());
    }

    private void deductActivityStock(ReservationAcceptedEvent event) {
        int updated = jdbc.update("""
                UPDATE flash_sale_activity
                   SET available_stock = available_stock - ?,
                       version = version + 1
                 WHERE activity_id = ?
                   AND product_id = ?
                   AND available_stock >= ?
                """, event.quantity(), event.activityId(), event.productId(), event.quantity());
        if (updated == 1) {
            return;
        }
        List<Map<String, Object>> facts = jdbc.queryForList("""
                SELECT product_id, available_stock
                  FROM flash_sale_activity
                 WHERE activity_id = ?
                 FOR UPDATE
                """, event.activityId());
        if (facts.size() == 1
                && ((Number) facts.get(0).get("product_id")).longValue() == event.productId()
                && ((Number) facts.get(0).get("available_stock")).longValue() < event.quantity()) {
            throw new SafeInventoryFailure("ACTIVITY_STOCK_INSUFFICIENT");
        }
        throw new ManualFactFailure("ACTIVITY_FACT_MISSING_OR_CONFLICTING");
    }

    private void deductCatalogStock(ReservationAcceptedEvent event) {
        int updated = jdbc.update("""
                UPDATE catalog_product
                   SET stock = stock - ?,
                       version = version + 1
                 WHERE product_id = ?
                   AND stock >= ?
                """, event.quantity(), event.productId(), event.quantity());
        if (updated == 1) {
            return;
        }
        List<Map<String, Object>> facts = jdbc.queryForList("""
                SELECT stock
                  FROM catalog_product
                 WHERE product_id = ?
                 FOR UPDATE
                """, event.productId());
        if (facts.size() == 1
                && ((Number) facts.get(0).get("stock")).longValue() < event.quantity()) {
            throw new SafeInventoryFailure("CATALOG_STOCK_INSUFFICIENT");
        }
        throw new ManualFactFailure("CATALOG_FACT_MISSING_OR_CONFLICTING");
    }

    private String verifyCommittedOrder(ReservationAcceptedEvent event) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT o.order_id, o.user_id, o.total_amount, o.currency AS order_currency,
                       r.activity_id, r.user_id AS reservation_user_id,
                       r.product_id, r.quantity, r.unit_price, r.reserved_amount,
                       r.currency AS reservation_currency, r.activity_version,
                       r.idempotency_key_hash, r.request_fingerprint,
                       r.status, r.order_id AS reservation_order_id,
                       i.product_id AS item_product_id, i.quantity AS item_quantity,
                       i.price AS item_price, i.line_amount
                  FROM sale_reservation r
                  JOIN sales_order o ON o.reservation_id = r.reservation_id
                  JOIN sales_order_item i ON i.order_id = o.order_id
                 WHERE r.reservation_no = ?
                 FOR UPDATE
                """, event.reservationNo());
        if (rows.size() != 1) {
            throw new ManualFactFailure("COMMITTED_ORDER_MISSING_OR_DUPLICATED");
        }
        Map<String, Object> row = rows.get(0);
        String orderId = String.valueOf(row.get("order_id"));
        boolean matches = orderId.equals(event.deterministicOrderId())
                && ((Number) row.get("user_id")).longValue() == event.userId()
                && ((Number) row.get("activity_id")).longValue() == event.activityId()
                && ((Number) row.get("reservation_user_id")).longValue() == event.userId()
                && ((Number) row.get("product_id")).longValue() == event.productId()
                && ((Number) row.get("quantity")).intValue() == event.quantity()
                && equalMoney((BigDecimal) row.get("unit_price"), event.unitPrice())
                && equalMoney((BigDecimal) row.get("reserved_amount"), event.totalAmount())
                && equalMoney((BigDecimal) row.get("total_amount"), event.totalAmount())
                && "CNY".equals(row.get("order_currency"))
                && "CNY".equals(row.get("reservation_currency"))
                && ((Number) row.get("activity_version")).intValue()
                == event.activityVersion()
                && event.idempotencyKeyHash().equals(row.get("idempotency_key_hash"))
                && event.requestFingerprint().equals(row.get("request_fingerprint"))
                && orderId.equals(row.get("reservation_order_id"))
                && ((Number) row.get("item_product_id")).longValue() == event.productId()
                && ((Number) row.get("item_quantity")).intValue() == event.quantity()
                && equalMoney((BigDecimal) row.get("item_price"), event.unitPrice())
                && equalMoney((BigDecimal) row.get("line_amount"), event.totalAmount())
                && ORDER_CREATED.equals(row.get("status"));
        if (!matches) {
            throw new ManualFactFailure("COMMITTED_ORDER_FACT_CONFLICT");
        }
        return orderId;
    }

    private void validateCompensationReservation(ReservationAcceptedEvent event) {
        List<ReservationRow> reservations = lockReservation(event.reservationNo());
        if (reservations.size() != 1
                || !immutableFactsMatch(reservations.get(0), event)
                || !Set.of(COMPENSATING, COMPENSATED).contains(reservations.get(0).status())
                || reservations.get(0).orderId() != null) {
            throw new ManualFactFailure("MYSQL_COMPENSATION_STATE_CONFLICT");
        }
    }

    private void validateCompensatedFacts(
            ReservationAcceptedEvent event,
            ProcessingRow processing,
            String compensationId,
            String reasonCode
    ) {
        if (!Objects.equals(processing.compensationId(), compensationId)
                || !Objects.equals(processing.reasonCode(), reasonCode)) {
            throw new ManualFactFailure("COMPENSATION_INTENT_CONFLICT");
        }
        validateCompensationReservation(event);
        ensureNoOrderBeforeCompensation(event);
    }

    private void ensureNoOrderBeforeCompensation(ReservationAcceptedEvent event) {
        Integer orderCount = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sales_order o
                  JOIN sale_reservation r ON r.reservation_id = o.reservation_id
                 WHERE r.reservation_no = ?
                """, Integer.class, event.reservationNo());
        if (orderCount != null && orderCount > 0) {
            throw new ManualFactFailure("ORDER_EXISTS_COMPENSATION_FORBIDDEN");
        }
    }

    private void markOrderCreated(long processingId, ReservationAcceptedEvent event, String orderId) {
        jdbc.update("""
                UPDATE seckill_event_processing
                   SET status = 'ORDER_CREATED',
                       attempts = attempts + 1,
                       next_attempt_at = NULL,
                       order_id = ?,
                       reason_code = NULL,
                       last_error = NULL,
                       version = version + 1
                 WHERE processing_id = ?
                """, orderId, processingId);
    }

    private void recordProcessedEvent(ReservationAcceptedEvent event) {
        jdbc.update("""
                INSERT INTO processed_event (consumer_name, event_id, event_type)
                VALUES (?, ?, 'RESERVATION_ACCEPTED')
                ON DUPLICATE KEY UPDATE event_id = VALUES(event_id)
                """, properties.getGroupName(), event.eventId());
    }

    private void markManualInCurrentTransaction(
            long processingId,
            String reasonCode,
            ReservationAcceptedEvent event,
            String streamKey,
            String streamEntryId
    ) {
        jdbc.update("""
                UPDATE seckill_event_processing
                   SET status = 'MANUAL_REVIEW',
                       attempts = attempts + 1,
                       next_attempt_at = NULL,
                       reason_code = ?,
                       last_error = ?,
                       version = version + 1
                 WHERE processing_id = ?
                """, reasonCode, "BUSINESS_FACT:" + reasonCode, processingId);
        recordIssue(
                reasonCode,
                "CRITICAL",
                event.activityId(),
                event.reservationNo(),
                streamKey,
                streamEntryId,
                event.payloadHash(),
                Map.of(
                        "schemaVersion", 1,
                        "classification", reasonCode,
                        "payloadHash", event.payloadHash()
                )
        );
    }

    private void recordIssue(
            String issueType,
            String severity,
            Long activityId,
            String reservationNo,
            String streamKey,
            String streamEntryId,
            String payloadHash,
            Map<String, ?> evidence
    ) {
        String issueKey = sha256(issueType + "\n" + nullToEmpty(streamKey) + "\n"
                + nullToEmpty(streamEntryId) + "\n" + nullToEmpty(reservationNo));
        jdbc.update("""
                INSERT INTO seckill_reconciliation_issue (
                    issue_key, issue_type, severity, status, activity_id,
                    reservation_no, stream_key, stream_entry_id,
                    evidence_version, evidence_summary
                ) VALUES (?, ?, ?, 'OPEN', ?, ?, ?, ?, 1, CAST(? AS JSON))
                ON DUPLICATE KEY UPDATE
                    status = CASE WHEN status = 'IGNORED' THEN status ELSE 'OPEN' END,
                    severity = VALUES(severity),
                    evidence_summary = VALUES(evidence_summary),
                    occurrences = occurrences + 1,
                    last_seen_at = CURRENT_TIMESTAMP(6),
                    resolved_at = NULL,
                    version = version + 1
                """,
                issueKey,
                issueType,
                severity,
                activityId,
                reservationNo,
                streamKey,
                streamEntryId,
                json(evidence)
        );
    }

    private void insertOutbox(
            String eventId,
            String aggregateType,
            String aggregateId,
            String eventType,
            Map<String, ?> payload
    ) {
        String serialized = json(payload);
        try {
            jdbc.update("""
                    INSERT INTO outbox_event (
                        event_id, aggregate_type, aggregate_id, event_type, payload
                    ) VALUES (?, ?, ?, ?, CAST(? AS JSON))
                    """,
                    eventId,
                    aggregateType,
                    aggregateId,
                    eventType,
                    serialized
            );
        } catch (DuplicateKeyException exception) {
            Integer identical = jdbc.queryForObject("""
                    SELECT COUNT(*)
                      FROM outbox_event
                     WHERE event_id = ?
                       AND aggregate_type = ?
                       AND aggregate_id = ?
                       AND event_type = ?
                       AND JSON_CONTAINS(payload, CAST(? AS JSON))
                       AND JSON_CONTAINS(CAST(? AS JSON), payload)
                    """,
                    Integer.class,
                    eventId,
                    aggregateType,
                    aggregateId,
                    eventType,
                    serialized,
                    serialized
            );
            if (identical == null || identical != 1) {
                throw new ManualFactFailure("OUTBOX_IMMUTABLE_CONFLICT");
            }
        }
    }

    private List<ProcessingRow> rowsForEvent(String eventId, boolean forUpdate) {
        return jdbc.query("""
                        SELECT processing_id, event_id, stream_key, stream_entry_id,
                               reservation_no, activity_id, user_id, payload_hash,
                               status, attempts, next_attempt_at, order_id,
                               compensation_id, reason_code
                          FROM seckill_event_processing
                         WHERE event_id = ?
                        """ + (forUpdate ? " FOR UPDATE" : ""),
                (resultSet, rowNum) -> processingRow(resultSet),
                eventId
        );
    }

    private ProcessingRow processingRow(java.sql.ResultSet resultSet) throws java.sql.SQLException {
        Timestamp nextAttempt = resultSet.getTimestamp("next_attempt_at");
        return new ProcessingRow(
                resultSet.getLong("processing_id"),
                resultSet.getString("event_id"),
                resultSet.getString("stream_key"),
                resultSet.getString("stream_entry_id"),
                resultSet.getString("reservation_no"),
                (Long) resultSet.getObject("activity_id"),
                (Long) resultSet.getObject("user_id"),
                resultSet.getString("payload_hash"),
                resultSet.getString("status"),
                resultSet.getInt("attempts"),
                nextAttempt == null ? null : nextAttempt.toInstant(),
                resultSet.getString("order_id"),
                resultSet.getString("compensation_id"),
                resultSet.getString("reason_code")
        );
    }

    private Duration backoff(int attempts) {
        double multiplier = Math.pow(properties.getRetryMultiplier(), Math.max(0, attempts - 1));
        double millis = properties.getRetryInitialBackoff().toMillis() * multiplier;
        long bounded = Math.min((long) millis, properties.getRetryMaxBackoff().toMillis());
        return Duration.ofMillis(Math.max(1, bounded));
    }

    private String sanitizedError(FailureKind kind, String reasonCode) {
        String value = kind.name() + ":" + reasonCode;
        return value.length() <= 512 ? value : value.substring(0, 512);
    }

    private String json(Map<String, ?> value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Safe evidence serialization failed", exception);
        }
    }

    private static boolean equalMoney(BigDecimal left, BigDecimal right) {
        return left != null && right != null && left.compareTo(right) == 0;
    }

    private static String firstReason(List<String> errors) {
        return errors.isEmpty() ? "EVENT_INVALID" : errors.get(0);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public enum FailureKind {
        TRANSIENT,
        SAFE_INVENTORY,
        MANUAL
    }

    public record ProcessOutcome(
            OutcomeType type,
            String orderId,
            String compensationId,
            String reasonCode
    ) {
        static ProcessOutcome created(String orderId) {
            return new ProcessOutcome(OutcomeType.ORDER_CREATED, orderId, null, null);
        }

        static ProcessOutcome duplicate(String orderId) {
            return new ProcessOutcome(OutcomeType.DUPLICATE_ORDER, orderId, null, null);
        }

        static ProcessOutcome compensating(String compensationId, String reasonCode) {
            return new ProcessOutcome(
                    OutcomeType.COMPENSATING,
                    null,
                    compensationId,
                    reasonCode
            );
        }

        static ProcessOutcome compensated(String compensationId, String reasonCode) {
            return new ProcessOutcome(
                    OutcomeType.COMPENSATED,
                    null,
                    compensationId,
                    reasonCode
            );
        }

        static ProcessOutcome manual(String reasonCode) {
            return new ProcessOutcome(OutcomeType.MANUAL_REVIEW, null, null, reasonCode);
        }

        static ProcessOutcome retryNotDue() {
            return new ProcessOutcome(OutcomeType.RETRY_NOT_DUE, null, null, null);
        }
    }

    public enum OutcomeType {
        ORDER_CREATED,
        DUPLICATE_ORDER,
        COMPENSATING,
        COMPENSATED,
        MANUAL_REVIEW,
        RETRY_NOT_DUE
    }

    public record FailureOutcome(
            FailureOutcomeType type,
            int attempts,
            String orderId,
            String compensationId,
            String reasonCode
    ) {
        static FailureOutcome retrying(int attempts) {
            return new FailureOutcome(FailureOutcomeType.RETRYING, attempts, null, null, null);
        }

        static FailureOutcome compensating(String compensationId, String reasonCode) {
            return new FailureOutcome(
                    FailureOutcomeType.COMPENSATING,
                    0,
                    null,
                    compensationId,
                    reasonCode
            );
        }

        static FailureOutcome compensated(String compensationId, String reasonCode) {
            return new FailureOutcome(
                    FailureOutcomeType.COMPENSATED,
                    0,
                    null,
                    compensationId,
                    reasonCode
            );
        }

        static FailureOutcome orderCreated(String orderId) {
            return new FailureOutcome(
                    FailureOutcomeType.ORDER_CREATED,
                    0,
                    orderId,
                    null,
                    null
            );
        }

        static FailureOutcome manual(String reasonCode) {
            return new FailureOutcome(
                    FailureOutcomeType.MANUAL_REVIEW,
                    0,
                    null,
                    null,
                    reasonCode
            );
        }
    }

    public enum FailureOutcomeType {
        RETRYING,
        COMPENSATING,
        COMPENSATED,
        ORDER_CREATED,
        MANUAL_REVIEW
    }

    private record ProcessingRow(
            long processingId,
            String eventId,
            String streamKey,
            String streamEntryId,
            String reservationNo,
            Long activityId,
            Long userId,
            String payloadHash,
            String status,
            int attempts,
            Instant nextAttemptAt,
            String orderId,
            String compensationId,
            String reasonCode
    ) {
    }

    private record ReservationRow(
            long reservationId,
            String reservationNo,
            long activityId,
            long userId,
            long productId,
            int quantity,
            BigDecimal unitPrice,
            BigDecimal reservedAmount,
            String currency,
            Integer activityVersion,
            String idempotencyKeyHash,
            String requestFingerprint,
            String status,
            String orderId
    ) {
    }

    public record CompensationRepairIntent(
            String compensationId,
            String reasonCode,
            boolean mysqlCompleted
    ) {
    }

    public static final class SafeInventoryFailure extends RuntimeException {
        public SafeInventoryFailure(String reasonCode) {
            super(reasonCode);
        }
    }

    public static final class ManualFactFailure extends RuntimeException {
        public ManualFactFailure(String reasonCode) {
            super(reasonCode);
        }
    }
}
