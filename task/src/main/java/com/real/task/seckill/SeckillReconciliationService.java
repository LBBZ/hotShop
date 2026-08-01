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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class SeckillReconciliationService {
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
        runBatch();
    }

    public ReconciliationReport runBatch() {
        Set<String> registered = redis.opsForSet().members(SeckillRedisKeys.reservationStreamRegistry());
        List<String> streams = registered == null
                ? List.of()
                : registered.stream()
                        .filter(key -> SeckillRedisKeys.activityIdFromReservationStream(key) != null)
                        .sorted(Comparator.naturalOrder())
                        .toList();
        List<String> orderedStreams = rotateAfter(streams, namedCheckpoint(REGISTRY_CHECKPOINT));
        int checked = 0;
        int findings = 0;
        int repairs = 0;
        for (String stream : orderedStreams) {
            StreamResult result = reconcileStream(
                    stream,
                    Math.max(0, properties.getReconciliationBatch() - checked)
            );
            checked += result.checked();
            findings += result.findings();
            repairs += result.repairs();
            updateNamedCheckpoint(REGISTRY_CHECKPOINT, stream);
            if (checked >= properties.getReconciliationBatch()) {
                break;
            }
        }
        MysqlReverseResult mysqlReverse =
                reconcileMysqlFacts(properties.getReconciliationBatch());
        findings += mysqlReverse.findings();
        repairs += mysqlReverse.repairs();
        if (findings > 0) {
            metrics.reconciliationFindings().increment(findings);
        }
        return new ReconciliationReport(
                streams.size(),
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
                       r.order_id,
                       COUNT(DISTINCT o.order_id) AS order_count,
                       COUNT(DISTINCT i.order_item_id) AS item_count,
                       COUNT(DISTINCT p.processing_id) AS processing_count
                  FROM sale_reservation r
                  LEFT JOIN sales_order o ON o.reservation_id = r.reservation_id
                  LEFT JOIN sales_order_item i ON i.order_id = o.order_id
                  LEFT JOIN seckill_event_processing p
                    ON p.reservation_no = r.reservation_no
                 WHERE r.reservation_id > ?
                   AND r.request_fingerprint IS NOT NULL
                 GROUP BY r.reservation_id, r.reservation_no, r.activity_id,
                          r.status, r.order_id
                 ORDER BY r.reservation_id
                 LIMIT ?
                """, cursor, batchSize);
        if (reservations.isEmpty() && cursor > 0) {
            updateNamedCheckpoint("mysql-seckill-reservations", "0");
            return new MysqlReverseResult(0, 0);
        }

        int findings = 0;
        for (Map<String, Object> row : reservations) {
            long reservationId = ((Number) row.get("reservation_id")).longValue();
            String reservationNo = String.valueOf(row.get("reservation_no"));
            Long activityId = ((Number) row.get("activity_id")).longValue();
            String status = String.valueOf(row.get("status"));
            long orderCount = ((Number) row.get("order_count")).longValue();
            long itemCount = ((Number) row.get("item_count")).longValue();
            long processingCount = ((Number) row.get("processing_count")).longValue();
            boolean orderFactsValid = "ORDER_CREATED".equals(status)
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
                                "itemCount", itemCount
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
            } else if (!hasMatchingStreamEvidence(reservationNo)) {
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

        Long orphanOrders = jdbc.queryForObject("""
                SELECT COUNT(*)
                  FROM sales_order o
                  LEFT JOIN sale_reservation r
                    ON r.reservation_id = o.reservation_id
                 WHERE o.reservation_id IS NOT NULL
                   AND r.reservation_id IS NULL
                """, Long.class);
        if (orphanOrders != null && orphanOrders > 0) {
            finding(
                    "MYSQL_ORDER_WITHOUT_RESERVATION",
                    "CRITICAL",
                    null,
                    null,
                    null,
                    null,
                    Map.of(
                            "schemaVersion", 1,
                            "orphanOrderCount", orphanOrders
                    )
            );
            findings++;
        }
        return new MysqlReverseResult(findings, 0);
    }

    private boolean hasMatchingStreamEvidence(String reservationNo) {
        List<Map<String, Object>> ledgers = jdbc.queryForList("""
                SELECT stream_key, stream_entry_id
                  FROM seckill_event_processing
                 WHERE reservation_no = ?
                 ORDER BY processing_id
                 LIMIT ?
                """, reservationNo, properties.getReconciliationBatch());
        for (Map<String, Object> ledger : ledgers) {
            String stream = String.valueOf(ledger.get("stream_key"));
            String entryId = String.valueOf(ledger.get("stream_entry_id"));
            if (SeckillRedisKeys.activityIdFromReservationStream(stream) == null) {
                continue;
            }
            List<MapRecord<String, Object, Object>> records =
                    redis.opsForStream().range(stream, Range.closed(entryId, entryId));
            if (records != null && records.size() == 1) {
                ReservationAcceptedEvent.ParseResult parsed =
                        ReservationAcceptedEvent.parse(
                                stream,
                                entryId,
                                records.get(0).getValue()
                        );
                if (parsed.valid()
                        && reservationNo.equals(parsed.event().reservationNo())) {
                    return true;
                }
            }
        }
        return false;
    }

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

        List<MapRecord<String, Object, Object>> all =
                redis.opsForStream().range(stream, Range.unbounded());
        if (all == null) {
            all = List.of();
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
        if (batch.isEmpty() && !all.isEmpty() && !"0-0".equals(checkpoint)) {
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

        ConservationResult conservation = conservation(activityId, stream, metadata, stockRaw, all);
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
                || (!slotExpected && userReservation != null)) {
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

    private ConservationResult conservation(
            long activityId,
            String stream,
            Map<Object, Object> metadata,
            String stockRaw,
            List<MapRecord<String, Object, Object>> all
    ) {
        long effectiveQuantity = 0;
        Set<String> reservations = new HashSet<>();
        int findings = 0;
        for (MapRecord<String, Object, Object> record : all) {
            ReservationAcceptedEvent.ParseResult parsed =
                    ReservationAcceptedEvent.parse(stream, record.getId().getValue(), record.getValue());
            if (!parsed.valid() || !reservations.add(parsed.event().reservationNo())) {
                continue;
            }
            Map<Object, Object> reservation = redis.opsForHash().entries(
                    SeckillRedisKeys.reservation(
                            activityId,
                            parsed.event().reservationNo()
                    )
            );
            String status = value(reservation, "status");
            if (EFFECTIVE_REDIS_STATUSES.contains(status)) {
                effectiveQuantity += parsed.event().quantity();
            }
            if ("COMPENSATED".equals(status)
                    && (!"1".equals(value(reservation, "stockRestored"))
                    || value(reservation, "compensationId") == null)) {
                finding(
                        "COMPENSATION_EVIDENCE_INVALID",
                        "CRITICAL",
                        activityId,
                        parsed.event().reservationNo(),
                        stream,
                        record.getId().getValue(),
                        Map.of(
                                "schemaVersion", 1,
                                "stockRestored", value(reservation, "stockRestored") != null,
                                "compensationIdPresent",
                                value(reservation, "compensationId") != null
                        )
                );
                findings++;
            }
        }
        long initial = number(value(metadata, "initialAvailableStock"));
        long current = number(stockRaw);
        if (current < 0 || initial - current != effectiveQuantity) {
            finding(
                    "REDIS_STOCK_CONSERVATION_VIOLATION",
                    "CRITICAL",
                    activityId,
                    null,
                    stream,
                    null,
                    Map.of(
                            "schemaVersion", 1,
                            "initialAvailableStock", initial,
                            "currentStock", current,
                            "effectiveReservedQuantity", effectiveQuantity,
                            "equationHolds", initial - current == effectiveQuantity
                    )
            );
            findings++;
        }

        List<Map<String, Object>> mysqlStocks = jdbc.queryForList("""
                SELECT a.available_stock, a.product_id, p.stock
                  FROM flash_sale_activity a
                  JOIN catalog_product p ON p.product_id = a.product_id
                 WHERE a.activity_id = ?
                """, activityId);
        if (mysqlStocks.size() == 1) {
            long productId = ((Number) mysqlStocks.get(0).get("product_id")).longValue();
            Long activitySuccessfulQuantity = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(r.quantity), 0)
                      FROM sale_reservation r
                      JOIN sales_order o ON o.reservation_id = r.reservation_id
                     WHERE r.activity_id = ?
                       AND r.status = 'ORDER_CREATED'
                    """, Long.class, activityId);
            Long productSuccessfulQuantity = jdbc.queryForObject("""
                    SELECT COALESCE(SUM(r.quantity), 0)
                      FROM sale_reservation r
                      JOIN sales_order o ON o.reservation_id = r.reservation_id
                     WHERE r.product_id = ?
                       AND r.status = 'ORDER_CREATED'
                    """, Long.class, productId);
            long activitySuccessful =
                    activitySuccessfulQuantity == null ? 0 : activitySuccessfulQuantity;
            long productSuccessful =
                    productSuccessfulQuantity == null ? 0 : productSuccessfulQuantity;
            long activityStock = ((Number) mysqlStocks.get(0).get("available_stock")).longValue();
            long initialCatalog = number(value(metadata, "initialCatalogStock"));
            long catalogStock = ((Number) mysqlStocks.get(0).get("stock")).longValue();
            boolean activityHolds = activityStock == initial - activitySuccessful;
            boolean catalogHolds =
                    initialCatalog == 0 || catalogStock == initialCatalog - productSuccessful;
            if (!activityHolds || !catalogHolds) {
                finding(
                        "MYSQL_STOCK_CONSERVATION_VIOLATION",
                        "CRITICAL",
                        activityId,
                        null,
                        stream,
                        null,
                        Map.of(
                                "schemaVersion", 1,
                                "initialActivityStock", initial,
                                "activityStock", activityStock,
                                "initialCatalogStock", initialCatalog,
                                "catalogStock", catalogStock,
                                "activitySuccessfulQuantity", activitySuccessful,
                                "productSuccessfulQuantity", productSuccessful,
                                "activityEquationHolds", activityHolds,
                                "catalogEquationHolds", catalogHolds
                        )
                );
                findings++;
            }
        }
        return new ConservationResult(findings, 0);
    }

    private PendingResult terminalPending(String stream) {
        List<PendingEntry> pending = pendingEntries(stream, properties.getReconciliationBatch());
        int findings = 0;
        int repairs = 0;
        for (PendingEntry entry : pending) {
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
                redis.opsForStream().range(stream, Range.closed(entryId, entryId));
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

    private List<PendingEntry> pendingEntries(String stream, int count) {
        PendingMessages pending = redis.opsForStream().pending(
                stream,
                properties.getGroupName(),
                Range.unbounded(),
                count
        );
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

    private static List<String> rotateAfter(List<String> streams, String previous) {
        if (streams.isEmpty() || previous == null || previous.isBlank()) {
            return streams;
        }
        int index = streams.indexOf(previous);
        if (index < 0 || index == streams.size() - 1) {
            return streams;
        }
        List<String> rotated = new ArrayList<>(streams.size());
        rotated.addAll(streams.subList(index + 1, streams.size()));
        rotated.addAll(streams.subList(0, index + 1));
        return List.copyOf(rotated);
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
