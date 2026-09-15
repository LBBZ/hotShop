package com.real.task.seckill;

import com.real.infrastructure.redis.SeckillRedisKeys;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.domain.Range;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.PendingMessage;
import org.springframework.data.redis.connection.stream.PendingMessages;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SeckillReconciliationService {
    private static final DefaultRedisScript<List> CONSERVATION_PAGE = new DefaultRedisScript<>();
    static {
        CONSERVATION_PAGE.setLocation(new ClassPathResource("redis/reconcile-conservation-page-v1.lua"));
        CONSERVATION_PAGE.setResultType(List.class);
    }
    private static final String REGISTRY_CHECKPOINT =
            "reservation-stream-registry";
    private static final Set<String> EFFECTIVE_REDIS_STATUSES =
            Set.of("RESERVED", "ORDER_CREATED", "COMPENSATING");
    private static final Set<String> TERMINAL_LEDGER_STATUSES =
            Set.of("ORDER_CREATED", "COMPENSATED", "QUARANTINED", "MANUAL_REVIEW");

    private final StringRedisTemplate redis;
    private final JdbcTemplate jdbc;
    private final SeckillOrderProperties properties;
    private final SeckillRedisReservationGateway reservationGateway;
    private final SeckillProcessingService processingService;
    private final SeckillOrderMetrics metrics;

    public SeckillReconciliationService(
            @Qualifier("seckillStringRedisTemplate") StringRedisTemplate redis,
            JdbcTemplate jdbc,
            SeckillOrderProperties properties,
            SeckillRedisReservationGateway reservationGateway,
            SeckillProcessingService processingService,
            SeckillOrderMetrics metrics
    ) {
        this.redis = redis;
        this.jdbc = jdbc;
        this.properties = properties;
        this.reservationGateway = reservationGateway;
        this.processingService = processingService;
        this.metrics = metrics;
    }

    @Scheduled(
            fixedDelayString =
                    "${hotshop.seckill.order-consumer.reconciliation-interval:5m}",
            initialDelayString =
                    "${hotshop.seckill.order-consumer.reconciliation-initial-delay:30s}"
    )
    public void scheduledReconciliation() {
        try {
            runBatch();
            metrics.inventory("reconciliation", "success");
        } catch (RuntimeException failure) {
            metrics.inventory("reconciliation", "failure");
            throw failure;
        }
    }

    public ReconciliationReport runBatch() {
        // One activity per invocation: even an empty/invalid stream consumes its
        // turn. Lexicographic keyset discovery is independent of registry size.
        String index = SeckillRedisKeys.reconciliationStreamIndex();
        long indexed = java.util.Objects.requireNonNullElse(redis.opsForZSet().zCard(index), 0L);
        long registered = java.util.Objects.requireNonNullElse(
                redis.opsForSet().size(SeckillRedisKeys.reservationStreamRegistry()), 0L);
        int checked = 0;
        int findings = 0;
        int repairs = 0;
        if (indexed != registered) {
            finding("RECONCILIATION_INDEX_UPGRADE_REQUIRED", "CRITICAL", null, null, null, null,
                    Map.of("schemaVersion", 1, "registeredStreams", registered,
                            "indexedStreams", indexed));
            findings++;
        }
        String after = namedCheckpoint(REGISTRY_CHECKPOINT);
        Range<String> range = after.isBlank() ? Range.unbounded()
                : Range.from(Range.Bound.exclusive(after)).to(Range.Bound.unbounded());
        Set<String> selected = redis.opsForZSet().rangeByLex(index, range, Limit.limit().count(1));
        if ((selected == null || selected.isEmpty()) && !after.isBlank()) {
            selected = redis.opsForZSet().rangeByLex(index, Range.unbounded(), Limit.limit().count(1));
        }
        if (selected != null && !selected.isEmpty()) {
            String stream = selected.iterator().next();
            StreamResult result = reconcileStream(stream, properties.getReconciliationBatch());
            checked = result.checked();
            findings += result.findings();
            repairs += result.repairs();
            updateNamedCheckpoint(REGISTRY_CHECKPOINT, stream);
        }
        MysqlReverseResult mysqlReverse =
                reconcileMysqlFacts(properties.getReconciliationBatch());
        findings += mysqlReverse.findings();
        repairs += mysqlReverse.repairs();
        if (findings > 0) {
            metrics.reconciliationFindings().increment(findings);
        }
        return new ReconciliationReport(
                (int) Math.min(Integer.MAX_VALUE, indexed),
                checked,
                findings,
                repairs,
                properties.isReconciliationDryRun(),
                properties.isAutoRepair()
        );
    }

    private MysqlReverseResult reconcileMysqlFacts(int batchSize) {
        long cursor = parseLong(namedCheckpoint("mysql-seckill-reservations"));
        List<Map<String, Object>> reservations = jdbc.queryForList("""
                SELECT r.reservation_id, r.reservation_no, r.activity_id, r.status,
                       r.order_id, r.request_fingerprint,
                       COUNT(DISTINCT o.order_id) AS order_count,
                       COUNT(DISTINCT i.order_item_id) AS item_count,
                       EXISTS(SELECT 1 FROM seckill_event_processing p
                               WHERE p.reservation_no = r.reservation_no) AS processing_count
                  FROM (SELECT reservation_id, reservation_no, activity_id, status, order_id, request_fingerprint
                          FROM sale_reservation
                         WHERE reservation_id > ?
                         ORDER BY reservation_id LIMIT ?) r
                  LEFT JOIN sales_order o ON o.reservation_id = r.reservation_id
                  LEFT JOIN LATERAL (
                       SELECT si.order_item_id FROM sales_order_item si
                        WHERE si.order_id = o.order_id LIMIT 2
                  ) i ON TRUE
                 GROUP BY r.reservation_id, r.reservation_no, r.activity_id,
                          r.status, r.order_id, r.request_fingerprint
                 ORDER BY r.reservation_id
                """, cursor, batchSize);
        if (reservations.isEmpty() && cursor > 0) {
            updateNamedCheckpoint("mysql-seckill-reservations", "0");
            return new MysqlReverseResult(0, 0);
        }

        int findings = 0;
        for (Map<String, Object> row : reservations) {
            long reservationId = ((Number) row.get("reservation_id")).longValue();
            if (row.get("request_fingerprint") == null) {
                updateNamedCheckpoint("mysql-seckill-reservations", Long.toString(reservationId));
                continue;
            }
            String reservationNo = String.valueOf(row.get("reservation_no"));
            Long activityId = ((Number) row.get("activity_id")).longValue();
            String status = String.valueOf(row.get("status"));
            long orderCount = ((Number) row.get("order_count")).longValue();
            long itemCount = ((Number) row.get("item_count")).longValue();
            long processingCount = ((Number) row.get("processing_count")).longValue();
            boolean orderFactsValid = Set.of("ORDER_CREATED", "CANCELED").contains(status)
                    ? orderCount == 1 && itemCount == 1 && row.get("order_id") != null
                    : orderCount == 0 && row.get("order_id") == null;
            if (!orderFactsValid) {
                finding(
                        "MYSQL_REVERSE_ORDER_CARDINALITY_CONFLICT",
                        "CRITICAL",
                        activityId,
                        reservationNo,
                        null,
                        null,
                        Map.of(
                                "schemaVersion", 1,
                                "reservationStatus", status,
                                "orderCount", orderCount,
                                "itemCountUpToTwo", itemCount
                        )
                );
                findings++;
            }
            if (processingCount == 0) {
                finding(
                        "MYSQL_RESERVATION_WITHOUT_PROCESSING_LEDGER",
                        "CRITICAL",
                        activityId,
                        reservationNo,
                        null,
                        null,
                        Map.of(
                                "schemaVersion", 1,
                                "reservationStatus", status
                        )
                );
                findings++;
            } else if (streamEvidence(reservationId, reservationNo) == EvidenceResult.MISSING) {
                finding(
                        "MYSQL_RESERVATION_WITHOUT_STREAM_EVIDENCE",
                        "CRITICAL",
                        activityId,
                        reservationNo,
                        null,
                        null,
                        Map.of(
                                "schemaVersion", 1,
                                "processingRowsPresent", true
                        )
                );
                findings++;
            }
            updateNamedCheckpoint(
                    "mysql-seckill-reservations",
                    Long.toString(reservationId)
            );
        }

        // Reverse orphan audit also has a keyset cursor; never COUNT all orders.
        String orderCursor = namedCheckpoint("mysql-seckill-orphan-orders");
        List<Map<String, Object>> orderPage = jdbc.queryForList("""
                SELECT o.order_id, o.reservation_id, r.reservation_id AS matched_reservation_id
                  FROM sales_order o
                  LEFT JOIN sale_reservation r ON r.reservation_id = o.reservation_id
                 WHERE o.order_id > ?
                 ORDER BY o.order_id LIMIT ?
                """, orderCursor, batchSize);
        for (Map<String, Object> order : orderPage) {
            if (order.get("reservation_id") != null && order.get("matched_reservation_id") == null) {
                finding("MYSQL_ORDER_WITHOUT_RESERVATION", "CRITICAL", null, null, null, null,
                        Map.of("schemaVersion", 1, "orderId", String.valueOf(order.get("order_id"))));
                findings++;
            }
            updateNamedCheckpoint("mysql-seckill-orphan-orders", String.valueOf(order.get("order_id")));
        }
        if (orderPage.isEmpty()) updateNamedCheckpoint("mysql-seckill-orphan-orders", "");
        return new MysqlReverseResult(findings, 0);
    }

    private EvidenceResult streamEvidence(long reservationId, String reservationNo) {
        // Processing stream references are immutable in the production ledger.
        // Inspect one candidate per reservation per round. Missing evidence is
        // reported only after the entire candidate range was exhausted, never
        // merely because a matching entry did not fit in this invocation.
        String name = "stream-evidence-" + reservationId;
        long cursor = parseLong(namedCheckpoint(name));
        List<Map<String, Object>> ledgers = jdbc.queryForList("""
                SELECT processing_id, stream_key, stream_entry_id
                  FROM seckill_event_processing
                 WHERE reservation_no = ? AND processing_id > ?
                 ORDER BY processing_id LIMIT 1
                """, reservationNo, cursor);
        if (ledgers.isEmpty()) {
            updateNamedCheckpoint(name, "0");
            return EvidenceResult.MISSING;
        }
        Map<String, Object> ledger = ledgers.getFirst();
        String stream = String.valueOf(ledger.get("stream_key"));
        String entryId = String.valueOf(ledger.get("stream_entry_id"));
        if (SeckillRedisKeys.activityIdFromReservationStream(stream) != null) {
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(
                    stream, Range.closed(entryId, entryId), Limit.limit().count(1));
            if (records != null && records.size() == 1) {
                ReservationAcceptedEvent.ParseResult parsed = ReservationAcceptedEvent.parse(
                        stream, entryId, records.getFirst().getValue());
                if (parsed.valid() && reservationNo.equals(parsed.event().reservationNo())) {
                    updateNamedCheckpoint(name, "0");
                    return EvidenceResult.MATCH;
                }
            }
        }
        updateNamedCheckpoint(name, String.valueOf(ledger.get("processing_id")));
        return EvidenceResult.IN_PROGRESS;
    }

    private enum EvidenceResult { MATCH, MISSING, IN_PROGRESS }

    private StreamResult reconcileStream(String stream, int remainingBatch) {
        if (remainingBatch <= 0) {
            return new StreamResult(0, 0, 0);
        }
        Long activityId = SeckillRedisKeys.activityIdFromReservationStream(stream);
        if (activityId == null) {
            return new StreamResult(0, 0, 0);
        }
        Map<Object, Object> metadata =
                redis.opsForHash().entries(SeckillRedisKeys.activityMetadata(activityId));
        String stockRaw = redis.opsForValue().get(SeckillRedisKeys.availableStock(activityId));
        int findings = 0;
        int repairs = 0;
        if (metadata == null || metadata.isEmpty() || !nonNegative(stockRaw)) {
            finding(
                    "REDIS_ACTIVITY_FACT_INVALID",
                    "CRITICAL",
                    activityId,
                    null,
                    stream,
                    null,
                    Map.of(
                            "schemaVersion", 1,
                            "metadataPresent", metadata != null && !metadata.isEmpty(),
                            "stockValid", nonNegative(stockRaw)
                    )
            );
            return new StreamResult(0, 1, 0);
        }

        String checkpoint = checkpoint(stream);
        Range<String> remaining = "0-0".equals(checkpoint)
                ? Range.unbounded()
                : Range.from(Range.Bound.exclusive(checkpoint))
                        .to(Range.Bound.unbounded());
        List<MapRecord<String, Object, Object>> batch =
                redis.opsForStream().range(
                        stream,
                        remaining,
                        Limit.limit().count(remainingBatch)
                );
        if (batch == null) {
            batch = List.of();
        }
        if (batch.isEmpty() && !"0-0".equals(checkpoint)) {
            updateCheckpoint(stream, "0-0");
            batch = redis.opsForStream().range(
                    stream,
                    Range.unbounded(),
                    Limit.limit().count(remainingBatch)
            );
            if (batch == null) {
                batch = List.of();
            }
        }

        for (MapRecord<String, Object, Object> record : batch) {
            ReservationAcceptedEvent.ParseResult parsed =
                    ReservationAcceptedEvent.parse(stream, record.getId().getValue(), record.getValue());
            if (!parsed.valid()) {
                finding(
                        "RECONCILIATION_POISON_EVENT",
                        "CRITICAL",
                        activityId,
                        null,
                        stream,
                        record.getId().getValue(),
                        Map.of(
                                "schemaVersion", 1,
                                "validationCodes", parsed.errors(),
                                "payloadHash", parsed.payloadHash()
                        )
                );
                findings++;
                checkedCheckpoint(stream, record.getId().getValue());
                continue;
            }
            EventResult result = reconcileEvent(
                    stream,
                    record.getId().getValue(),
                    parsed.event()
            );
            findings += result.findings();
            repairs += result.repairs();
            checkedCheckpoint(stream, record.getId().getValue());
        }

        ConservationResult conservation = conservation(activityId, stream);
        findings += conservation.findings();
        repairs += conservation.repairs();
        PendingResult pending = terminalPending(stream);
        findings += pending.findings();
        repairs += pending.repairs();
        return new StreamResult(batch.size(), findings, repairs);
    }

    private EventResult reconcileEvent(
            String stream,
            String entryId,
            ReservationAcceptedEvent event
    ) {
        int findings = 0;
        int repairs = 0;
        SeckillRedisReservationGateway.ReservationProof proof = reservationGateway.verify(event);
        if (!proof.valid()) {
            finding(
                    proof.reasonCode(),
                    "CRITICAL",
                    event.activityId(),
                    event.reservationNo(),
                    stream,
                    entryId,
                    Map.of(
                            "schemaVersion", 1,
                            "payloadHash", event.payloadHash(),
                            "classification", proof.reasonCode()
                    )
            );
            return new EventResult(1, 0);
        }
        String userReservation = redis.opsForValue().get(
                SeckillRedisKeys.userReservation(event.activityId(), event.userId())
        );
        boolean slotExpected = EFFECTIVE_REDIS_STATUSES.contains(proof.status());
        if ((slotExpected && !event.reservationNo().equals(userReservation))
                || (!slotExpected && event.reservationNo().equals(userReservation))) {
            finding(
                    "REDIS_USER_SLOT_CONFLICT",
                    "CRITICAL",
                    event.activityId(),
                    event.reservationNo(),
                    stream,
                    entryId,
                    Map.of(
                            "schemaVersion", 1,
                            "reservationStatus", proof.status(),
                            "slotPresent", userReservation != null,
                            "slotMatches", event.reservationNo().equals(userReservation)
                    )
            );
            findings++;
        }

        List<Map<String, Object>> mysql = jdbc.queryForList("""
                SELECT r.status AS reservation_status, r.order_id,
                       COUNT(o.order_id) AS order_count
                  FROM sale_reservation r
                  LEFT JOIN sales_order o ON o.reservation_id = r.reservation_id
                 WHERE r.reservation_no = ?
                 GROUP BY r.reservation_id, r.status, r.order_id
                """, event.reservationNo());
        if (mysql.size() > 1
                || (!mysql.isEmpty()
                && ((Number) mysql.get(0).get("order_count")).longValue() > 1)) {
            finding(
                    "MYSQL_RESERVATION_ORDER_CARDINALITY_CONFLICT",
                    "CRITICAL",
                    event.activityId(),
                    event.reservationNo(),
                    stream,
                    entryId,
                    Map.of(
                            "schemaVersion", 1,
                            "reservationRows", mysql.size(),
                            "orderCount", mysql.isEmpty()
                                    ? 0
                                    : ((Number) mysql.get(0).get("order_count")).longValue()
                    )
            );
            findings++;
        }
        if (!mysql.isEmpty()) {
            Map<String, Object> row = mysql.get(0);
            String mysqlStatus = String.valueOf(row.get("reservation_status"));
            long orderCount = ((Number) row.get("order_count")).longValue();
            if ("ORDER_CREATED".equals(mysqlStatus) && orderCount != 1) {
                finding(
                        "MYSQL_ORDER_CREATED_WITHOUT_ONE_ORDER",
                        "CRITICAL",
                        event.activityId(),
                        event.reservationNo(),
                        stream,
                        entryId,
                        Map.of(
                                "schemaVersion", 1,
                                "orderCount", orderCount
                        )
                );
                findings++;
            }
            if ("ORDER_CREATED".equals(mysqlStatus) && !"ORDER_CREATED".equals(proof.status())) {
                finding(
                        "REDIS_ORDER_FINALIZE_MISSING",
                        "WARNING",
                        event.activityId(),
                        event.reservationNo(),
                        stream,
                        entryId,
                        Map.of(
                                "schemaVersion", 1,
                                "mysqlStatus", mysqlStatus,
                                "redisStatus", proof.status()
                        )
                );
                findings++;
                if (repairEnabled()) {
                    try {
                        String orderId =
                                processingService.validateCommittedOrderForRepair(event);
                        if (reservationGateway.finalizeOrder(event, orderId).successful()) {
                            repairs++;
                        }
                    } catch (SeckillProcessingService.ManualFactFailure exception) {
                        finding(
                                "AUTO_REPAIR_ORDER_EVIDENCE_CONFLICT",
                                "CRITICAL",
                                event.activityId(),
                                event.reservationNo(),
                                stream,
                                entryId,
                                Map.of(
                                        "schemaVersion", 1,
                                        "classification", exception.getMessage()
                                )
                        );
                        findings++;
                    }
                }
            }
        }

        List<Map<String, Object>> intents = jdbc.queryForList("""
                SELECT status, compensation_id, reason_code
                  FROM seckill_event_processing
                 WHERE event_id = ?
                """, event.eventId());
        if (intents.size() == 1
                && "COMPENSATING".equals(intents.get(0).get("status"))) {
            finding(
                    "COMPENSATION_COMPLETION_REQUIRED",
                    "WARNING",
                    event.activityId(),
                    event.reservationNo(),
                    stream,
                    entryId,
                    Map.of(
                            "schemaVersion", 1,
                            "intentPersisted", true,
                            "redisStatus", proof.status()
                    )
            );
            findings++;
            if (repairEnabled()) {
                try {
                    SeckillProcessingService.CompensationRepairIntent intent =
                            processingService.validateCompensationForRepair(event);
                    SeckillRedisReservationGateway.CompensationResult result =
                            reservationGateway.compensate(
                                    event,
                                    intent.compensationId(),
                                    intent.reasonCode()
                            );
                    if (result.successful()) {
                        processingService.finishCompensation(
                                event,
                                intent.compensationId(),
                                intent.reasonCode()
                        );
                        repairs++;
                    }
                } catch (SeckillProcessingService.ManualFactFailure exception) {
                    finding(
                            "AUTO_REPAIR_COMPENSATION_EVIDENCE_CONFLICT",
                            "CRITICAL",
                            event.activityId(),
                            event.reservationNo(),
                            stream,
                            entryId,
                            Map.of(
                                    "schemaVersion", 1,
                                    "classification", exception.getMessage()
                            )
                    );
                    findings++;
                }
            }
        }
        return new EventResult(findings, repairs);
    }

    private ConservationResult conservation(long activityId, String stream) {
        int findings = 0;
        List<?> page = redis.execute(CONSERVATION_PAGE,
                List.of(SeckillRedisKeys.activityMetadata(activityId),
                        SeckillRedisKeys.availableStock(activityId), stream,
                        SeckillRedisKeys.conservationCheckpoint(activityId)),
                Integer.toString(properties.getReconciliationBatch()),
                SeckillRedisKeys.reservation(activityId, ""));
        if (page == null || page.size() != 8) throw new IllegalStateException("Invalid conservation page");
        updateNamedCheckpoint("conservation-" + activityId,
                page.get(0) + ";fence=" + page.get(6) + ";scanned=" + page.get(1)
                        + ";restarted=" + page.get(7));
        // IN_PROGRESS is persisted and observable; it is never a successful audit.
        // A busy activity may restart until a complete unchanged writer epoch fits.
        if ("COMPLETE".equals(page.get(0).toString())) {
            long quantity = number(page.get(2).toString());
            long initial = number(page.get(3).toString());
            long current = number(page.get(4).toString());
            long invalid = number(page.get(5).toString());
            if (invalid > 0 || !nonNegative(page.get(3).toString())
                    || !nonNegative(page.get(4).toString()) || initial - current != quantity) {
                finding("REDIS_STOCK_CONSERVATION_VIOLATION", "CRITICAL", activityId,
                        null, stream, null, Map.of("schemaVersion", 2,
                                "initialAvailableStock", initial, "currentStock", current,
                                "effectiveReservedQuantity", quantity, "invalidFacts", invalid,
                                "inventoryFence", page.get(6).toString(),
                                "equationHolds", initial - current == quantity));
                findings++;
            }
        }

        // Product inventory and activity quota each have their own migration-time
        // accounting baseline. Every authorized stock delta updates the matching
        // expected balance in the same row/transaction. Read both together: no
        // activity-load snapshot, historical SUM, or cross-page MySQL snapshot.
        List<Map<String, Object>> stocks = jdbc.queryForList("""
                SELECT a.available_stock, a.expected_available_stock,
                       p.stock, p.expected_stock, a.product_id
                  FROM flash_sale_activity a
                  JOIN catalog_product p ON p.product_id = a.product_id
                 WHERE a.activity_id = ?
                """, activityId);
        if (stocks.isEmpty()) {
            finding("MYSQL_ACTIVITY_FACT_MISSING", "CRITICAL", activityId, null, stream, null,
                    Map.of("schemaVersion", 1, "activityId", activityId));
            findings++;
        } else {
            Map<String, Object> row = stocks.getFirst();
            long activityStock = ((Number) row.get("available_stock")).longValue();
            long activityExpected = ((Number) row.get("expected_available_stock")).longValue();
            long stock = ((Number) row.get("stock")).longValue();
            long expected = ((Number) row.get("expected_stock")).longValue();
            if (stock != expected || activityStock != activityExpected) {
                finding("MYSQL_STOCK_CONSERVATION_VIOLATION", "CRITICAL", activityId,
                        null, stream, null, Map.of("schemaVersion", 2,
                                "productId", row.get("product_id"),
                                "catalogStock", stock, "expectedCatalogStock", expected,
                                "activityStock", activityStock, "expectedActivityStock", activityExpected,
                                "catalogEquationHolds", stock == expected,
                                "activityEquationHolds", activityStock == activityExpected));
                findings++;
            }
        }
        return new ConservationResult(findings, 0);
    }

    private PendingResult terminalPending(String stream) {
        String pendingName = "pending-" + SeckillRedisKeys.activityIdFromReservationStream(stream);
        String pendingCursor = namedCheckpoint(pendingName);
        List<PendingEntry> pending = pendingEntries(stream, properties.getReconciliationBatch(), pendingCursor);
        if (pending.isEmpty()) updateNamedCheckpoint(pendingName, "");
        int findings = 0;
        int repairs = 0;
        for (PendingEntry entry : pending) {
            updateNamedCheckpoint(pendingName, entry.entryId());
            List<Map<String, Object>> rows = jdbc.queryForList("""
                    SELECT status, payload_hash
                      FROM seckill_event_processing
                     WHERE stream_key = ?
                       AND stream_entry_id = ?
                    """, stream, entry.entryId());
            if (rows.size() == 1
                    && TERMINAL_LEDGER_STATUSES.contains(String.valueOf(rows.get(0).get("status")))) {
                String processingStatus = String.valueOf(rows.get(0).get("status"));
                finding(
                        "TERMINAL_PROCESSING_STILL_PENDING",
                        "WARNING",
                        SeckillRedisKeys.activityIdFromReservationStream(stream),
                        null,
                        stream,
                        entry.entryId(),
                        Map.of(
                                "schemaVersion", 1,
                                "processingStatus", processingStatus,
                                "deliveryCount", entry.deliveryCount(),
                                "idleMs", entry.idleMs()
                        )
                );
                findings++;
                if (repairEnabled() && redisTerminalSafeToAck(
                        stream,
                        entry.entryId(),
                        processingStatus,
                        String.valueOf(rows.get(0).get("payload_hash"))
                )) {
                    Long acknowledged = redis.opsForStream().acknowledge(
                            stream,
                            properties.getGroupName(),
                            entry.entryId()
                    );
                    if (acknowledged != null && acknowledged == 1) {
                        repairs++;
                    }
                }
            }
        }
        return new PendingResult(findings, repairs);
    }

    private boolean redisTerminalSafeToAck(
            String stream,
            String entryId,
            String processingStatus,
            String payloadHash
    ) {
        if ("QUARANTINED".equals(processingStatus) || "MANUAL_REVIEW".equals(processingStatus)) {
            return true;
        }
        List<MapRecord<String, Object, Object>> records =
                redis.opsForStream().range(stream, Range.closed(entryId, entryId), Limit.limit().count(1));
        if (records == null || records.size() != 1) {
            return false;
        }
        ReservationAcceptedEvent.ParseResult parsed = ReservationAcceptedEvent.parse(
                stream,
                entryId,
                records.get(0).getValue()
        );
        if (!parsed.valid() || !payloadHash.equals(parsed.payloadHash())) {
            return false;
        }
        SeckillRedisReservationGateway.ReservationProof proof =
                reservationGateway.verify(parsed.event());
        return proof.valid()
                && (("ORDER_CREATED".equals(processingStatus)
                && "ORDER_CREATED".equals(proof.status()))
                || ("COMPENSATED".equals(processingStatus)
                && "COMPENSATED".equals(proof.status())));
    }

    private List<PendingEntry> pendingEntries(String stream, int count, String cursor) {
        PendingMessages pending;
        try {
            pending = redis.opsForStream().pending(
                    stream,
                    properties.getGroupName(),
                    cursor.isBlank() ? Range.unbounded()
                            : Range.from(Range.Bound.exclusive(cursor)).to(Range.Bound.unbounded()),
                    count
            );
        } catch (org.springframework.dao.DataAccessException failure) {
            Throwable cause = failure.getMostSpecificCause();
            // Loading registers an activity before a consumer creates its group.
            // With no group there is no PEL to inspect. Only this Redis error
            // means an empty PEL; connectivity, timeout and WRONGTYPE still fail.
            if (cause instanceof io.lettuce.core.RedisCommandExecutionException
                    && cause.getMessage() != null && cause.getMessage().startsWith("NOGROUP ")) {
                return List.of();
            }
            throw failure;
        }
        List<PendingEntry> result = new ArrayList<>();
        if (pending != null) {
            for (PendingMessage entry : pending) {
                result.add(new PendingEntry(
                        entry.getIdAsString(),
                        entry.getElapsedTimeSinceLastDelivery().toMillis(),
                        entry.getTotalDeliveryCount()
                ));
            }
        }
        return result;
    }

    private void finding(
            String type,
            String severity,
            Long activityId,
            String reservationNo,
            String stream,
            String entryId,
            Map<String, ?> evidence
    ) {
        processingService.recordReconciliationIssue(
                type,
                severity,
                activityId,
                reservationNo,
                stream,
                entryId,
                evidence
        );
    }

    private String checkpoint(String stream) {
        List<String> values = jdbc.query("""
                SELECT cursor_value
                  FROM seckill_reconciliation_checkpoint
                 WHERE checkpoint_name = ?
                """, (resultSet, rowNum) -> resultSet.getString(1), checkpointName(stream));
        return values.isEmpty() ? "0-0" : values.get(0);
    }

    private void checkedCheckpoint(String stream, String entryId) {
        updateCheckpoint(stream, entryId);
    }

    private void updateCheckpoint(String stream, String entryId) {
        updateNamedCheckpoint(checkpointName(stream), entryId);
    }

    private String namedCheckpoint(String checkpointName) {
        List<String> values = jdbc.query("""
                SELECT cursor_value
                  FROM seckill_reconciliation_checkpoint
                 WHERE checkpoint_name = ?
                """, (resultSet, rowNum) -> resultSet.getString(1), checkpointName);
        return values.isEmpty() ? "" : values.get(0);
    }

    private void updateNamedCheckpoint(String checkpointName, String cursorValue) {
        jdbc.update("""
                INSERT INTO seckill_reconciliation_checkpoint (
                    checkpoint_name, cursor_value
                ) VALUES (?, ?)
                ON DUPLICATE KEY UPDATE
                    cursor_value = VALUES(cursor_value),
                    version = version + 1
                """, checkpointName, cursorValue);
    }

    private String checkpointName(String stream) {
        Long activityId = SeckillRedisKeys.activityIdFromReservationStream(stream);
        return "reservation-stream-" + activityId;
    }

    private boolean repairEnabled() {
        return !properties.isReconciliationDryRun() && properties.isAutoRepair();
    }

    private static boolean nonNegative(String value) {
        return value != null && value.matches("^(0|[1-9][0-9]*)$");
    }

    private static String value(Map<Object, Object> values, String key) {
        if (values == null) {
            return null;
        }
        Object value = values.get(key);
        return value == null ? null : value.toString();
    }

    private static long number(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    private static long parseLong(String value) {
        try {
            return Long.parseLong(value);
        } catch (RuntimeException exception) {
            return 0;
        }
    }

    public record ReconciliationReport(
            int discoveredStreams,
            int checkedEvents,
            int findings,
            int repairs,
            boolean dryRun,
            boolean autoRepair
    ) {
    }

    private record EventResult(int findings, int repairs) {
    }

    private record StreamResult(int checked, int findings, int repairs) {
    }

    private record ConservationResult(int findings, int repairs) {
    }

    private record PendingResult(int findings, int repairs) {
    }

    private record MysqlReverseResult(int findings, int repairs) {
    }

    private record PendingEntry(String entryId, long idleMs, long deliveryCount) {
    }
}
