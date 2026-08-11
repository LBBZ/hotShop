package com.real.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class SchemaConstraintTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_constraints")
            .withUsername("hotshop")
            .withPassword("hotshop-test")
            .withCommand("--log-bin-trust-function-creators=1");

    private static Flyway flyway;
    private static int initialMigrationCount;
    private static final AtomicLong REFRESH_TOKEN_IDS = new AtomicLong(1);

    @BeforeAll
    static void migrateEmptyDatabase() {
        flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
        initialMigrationCount = flyway.migrate().migrationsExecuted;
    }

    @Test
    void emptyDatabaseMigratesToLatestAndValidates() {
        assertThat(initialMigrationCount).isEqualTo(9);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1.8");
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void repeatedMigrateDoesNotApplyChanges() {
        MigrateResult repeated = flyway.migrate();
        assertThat(repeated.migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    void duplicateUsernameIsRejected() throws SQLException {
        try (Connection connection = connection()) {
            insertUser(connection, "duplicate-name", "first@hotshop.invalid");
            assertConstraintViolation(() -> insertUser(connection, "duplicate-name", "second@hotshop.invalid"));
        }
    }

    @Test
    void duplicateEmailIsRejected() throws SQLException {
        try (Connection connection = connection()) {
            insertUser(connection, "email-owner-1", "duplicate@hotshop.invalid");
            assertConstraintViolation(() -> insertUser(connection, "email-owner-2", "duplicate@hotshop.invalid"));
        }
    }

    @Test
    void illegalStatusIsRejected() throws SQLException {
        try (Connection connection = connection()) {
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO catalog_product (sku, name, price, stock, status)
                    VALUES ('INVALID-STATUS', 'invalid', 1.00, 1, 'UNKNOWN')
                    """));
        }
    }

    @Test
    void negativeMoneyIsRejected() throws SQLException {
        try (Connection connection = connection()) {
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO payment_order (payment_no, order_id, amount, status)
                    VALUES ('NEGATIVE-PAYMENT', 'missing-order', -0.01, 'PENDING')
                    """));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void zeroOrNegativeQuantityIsRejected(int quantity) throws SQLException {
        try (Connection connection = connection()) {
            assertConstraintViolation(() -> {
                try (PreparedStatement statement = connection.prepareStatement("""
                        INSERT INTO sales_order_item
                            (order_id, product_id, quantity, price, line_amount)
                        VALUES (?, 999999, ?, 10.00, ?)
                        """)) {
                    statement.setString(1, "quantity-" + quantity);
                    statement.setInt(2, quantity);
                    statement.setBigDecimal(3, BigDecimal.TEN.multiply(BigDecimal.valueOf(quantity)));
                    statement.executeUpdate();
                }
            });
        }
    }

    @Test
    void negativeInventoryIsRejected() throws SQLException {
        try (Connection connection = connection()) {
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO catalog_product (sku, name, price, stock, status)
                    VALUES ('NEGATIVE-STOCK', 'invalid', 1.00, -1, 'ACTIVE')
                    """));
        }
    }

    @Test
    void activityAllowsOnlyOneEffectiveReservationPerUser() throws SQLException {
        try (Connection connection = connection()) {
            insertReservation(connection, "reservation-one", 700001, 800001, "RESERVED");
            assertConstraintViolation(() ->
                    insertReservation(connection, "reservation-two", 700001, 800001, "ORDER_CREATED"));

            insertReservation(connection, "reservation-compensated", 700002, 800002, "COMPENSATED");
            insertReservation(connection, "reservation-after-compensation", 700002, 800002, "RESERVED");
        }
    }

    @Test
    void paymentBusinessNumberIsUnique() throws SQLException {
        try (Connection connection = connection()) {
            insertPayment(connection, "payment-business-key", "order-a");
            assertConstraintViolation(() -> insertPayment(connection, "payment-business-key", "order-b"));
        }
    }

    @Test
    void consumerAndEventFormTheProcessedEventDeduplicationKey() throws SQLException {
        try (Connection connection = connection()) {
            String eventId = UUID.randomUUID().toString();
            insertProcessedEvent(connection, "order-projector", eventId);
            assertConstraintViolation(() -> insertProcessedEvent(connection, "order-projector", eventId));
            insertProcessedEvent(connection, "audit-projector", eventId);
        }
    }

    @Test
    void outboxEventIdRemainsUnique() throws SQLException {
        try (Connection connection = connection()) {
            String eventId = UUID.randomUUID().toString();
            insertOutbox(connection, eventId);
            assertConstraintViolation(() -> insertOutbox(connection, eventId));
        }
    }

    @Test
    void outboxLeaseTokenAndExpiryMustAppearTogether() throws SQLException {
        try (Connection connection = connection()) {
            String eventId = UUID.randomUUID().toString();
            insertOutbox(connection, eventId);
            assertConstraintViolation(() -> executeUpdate(connection, """
                    UPDATE outbox_event SET lease_token = '%s', lease_expires_at = NULL
                    WHERE event_id = '%s'
                    """.formatted(UUID.randomUUID(), eventId)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    UPDATE outbox_event SET lease_token = NULL, lease_expires_at = UTC_TIMESTAMP(6)
                    WHERE event_id = '%s'
                    """.formatted(eventId)));
        }
    }

    @Test
    void outboxConsecutiveAttemptsCannotBeNegative() throws SQLException {
        assertNegativeOutboxCounterRejected("consecutive_attempts");
    }

    @Test
    void outboxManualReplayCountCannotBeNegative() throws SQLException {
        assertNegativeOutboxCounterRejected("manual_replay_count");
    }

    @Test
    void outboxVersionCannotBeNegative() throws SQLException {
        assertNegativeOutboxCounterRejected("version");
    }

    private static void assertNegativeOutboxCounterRejected(String column) throws SQLException {
        try (Connection connection = connection()) {
            String eventId = UUID.randomUUID().toString();
            insertOutbox(connection, eventId);
            assertConstraintViolation(() -> executeUpdate(connection, """
                    UPDATE outbox_event SET %s = -1 WHERE event_id = '%s'
                    """.formatted(column, eventId)));
        }
    }

    @Test
    void outboxClaimAndFailedCursorIndexesHaveRequiredColumnOrder() throws SQLException {
        try (Connection connection = connection()) {
            assertThat(indexColumns(connection, "outbox_event", "idx_outbox_claim"))
                    .isEqualTo("status,available_at,lease_expires_at,outbox_id");
            assertThat(indexColumns(connection, "outbox_event", "idx_outbox_failed_cursor"))
                    .isEqualTo("status,outbox_id");
            assertThat(indexColumns(connection, "outbox_event", "uk_outbox_event_id"))
                    .isEqualTo("event_id");
        }
    }

    @Test
    void mockPaymentLedgerEnforcesCallbackNonceAndClaimIndexes() throws SQLException {
        try (Connection connection = connection()) {
            String callbackId = UUID.randomUUID().toString();
            insertCallback(connection, callbackId, "a".repeat(64), "MOCK_" + "1".repeat(32));
            assertConstraintViolation(() -> insertCallback(
                    connection, callbackId, "b".repeat(64), "MOCK_" + "2".repeat(32)));
            insertNonce(connection, "c".repeat(64), callbackId);
            assertConstraintViolation(() -> insertNonce(connection, "c".repeat(64), UUID.randomUUID().toString()));
            assertThat(indexColumns(connection, "payment_callback_ledger", "uk_payment_callback_id"))
                    .isEqualTo("callback_id");
            assertThat(indexColumns(connection, "payment_callback_nonce", "uk_payment_callback_nonce_hash"))
                    .isEqualTo("nonce_hash");
            assertThat(indexColumns(connection, "payment_order", "idx_payment_order_order_status"))
                    .isEqualTo("order_id,status,payment_id");
        }
    }

    @Test
    void mockPaymentChecksRejectInvalidProviderOutcomeHashAndStatus() throws SQLException {
        try (Connection connection = connection()) {
            assertConstraintViolation(() -> executeUpdate(connection, """
                INSERT INTO payment_callback_ledger(callback_id,payload_hash,provider,payment_no,
                  provider_transaction_no,outcome,amount,currency,occurred_at,receive_result,business_result)
                VALUES(UUID(),'not-a-hash','PUBLIC','bad','bad','UNKNOWN',-1,'USD',UTC_TIMESTAMP(6),'ACCEPTED','IDEMPOTENT')
                """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                INSERT INTO payment_order(payment_no,order_id,amount,status)
                VALUES('invalid-late','order-invalid-late',1.00,'REFUNDED')
                """));
            executeUpdate(connection, """
                INSERT INTO payment_order(payment_no,order_id,amount,status)
                VALUES('valid-late','order-valid-late',1.00,'LATE_SUCCEEDED')
                """);
        }
    }

    @Test
    void seckillProcessingLedgerEnforcesBothEventAndStreamEntryIdentity() throws SQLException {
        try (Connection connection = connection()) {
            insertSeckillProcessing(
                    connection,
                    "evt_" + "a".repeat(32),
                    "hotshop:seckill:v1:{hotshop-seckill-v1}:activity:1:reservations",
                    "1-0",
                    "RETRYING"
            );
            assertConstraintViolation(() -> insertSeckillProcessing(
                    connection,
                    "evt_" + "a".repeat(32),
                    "hotshop:seckill:v1:{hotshop-seckill-v1}:activity:1:reservations",
                    "2-0",
                    "ORDER_CREATED"
            ));
            assertConstraintViolation(() -> insertSeckillProcessing(
                    connection,
                    "evt_" + "b".repeat(32),
                    "hotshop:seckill:v1:{hotshop-seckill-v1}:activity:1:reservations",
                    "1-0",
                    "ORDER_CREATED"
            ));
            assertConstraintViolation(() -> insertSeckillProcessing(
                    connection,
                    "evt_" + "c".repeat(32),
                    "hotshop:seckill:v1:{hotshop-seckill-v1}:activity:1:reservations",
                    "3-0",
                    "ACKED"
            ));
        }
    }

    @Test
    void concurrentRefreshTokenRotationAllowsOnlyOneSuccessor() throws Exception {
        long parentTokenId;
        try (Connection connection = connection()) {
            parentTokenId = insertRefreshToken(connection, "a".repeat(64), null);
        }

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(
                    rotationAttempt(ready, start, parentTokenId, "b".repeat(64))
            );
            Future<Boolean> second = executor.submit(
                    rotationAttempt(ready, start, parentTokenId, "c".repeat(64))
            );

            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void refreshTokenCannotReferenceItselfAsParent() throws SQLException {
        try (Connection connection = connection()) {
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO refresh_token (
                        refresh_token_id, token_hash, csrf_hash, family_id, user_id,
                        session_type, parent_token_id,
                        issuer, audience, expires_at
                    ) VALUES (
                        9000000, 'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
                        'eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee',
                        '00000000-0000-0000-0000-000000000001', 1, 'USER', 9000000,
                        'hotshop', 'hotshop', DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY)
                    )
                    """));
        }
    }

    @Test
    void purchaseDraftPersistsOnlySnapshotsWithoutChangingInventoryOrOrders() throws SQLException {
        String draftId = UUID.randomUUID().toString();
        try (Connection connection = connection()) {
            executeUpdate(connection, """
                    INSERT INTO catalog_product (product_id, sku, name, price, stock, status)
                    VALUES (980001, 'AGENT-DRAFT-SNAPSHOT', 'Agent draft product', 12.50, 17, 'ACTIVE')
                    """);
            executeUpdate(connection, """
                    INSERT INTO purchase_draft (
                        draft_id, user_id, action_type, parameter_digest, valid_until
                    ) VALUES (
                        '%s', 880001, 'CREATE_ORDER', '%s',
                        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE)
                    )
                    """.formatted(draftId, "a".repeat(64)));
            executeUpdate(connection, """
                    INSERT INTO purchase_draft_item (
                        draft_id, product_id, quantity, product_name_snapshot,
                        unit_price_snapshot, line_amount_snapshot
                    ) VALUES ('%s', 980001, 2, 'Agent draft product', 12.50, 25.00)
                    """.formatted(draftId));

            assertThat(singleInt(connection,
                    "SELECT stock FROM catalog_product WHERE product_id = 980001"))
                    .isEqualTo(17);
            assertThat(singleInt(connection,
                    "SELECT COUNT(*) FROM sales_order WHERE user_id = 880001"))
                    .isZero();
            assertThat(singleInt(connection,
                    "SELECT COUNT(*) FROM sale_reservation WHERE user_id = 880001"))
                    .isZero();

            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO purchase_draft_item (
                        draft_id, product_id, quantity, product_name_snapshot,
                        unit_price_snapshot, line_amount_snapshot
                    ) VALUES ('%s', 980002, 101, 'Too many', 1.00, 101.00)
                    """.formatted(draftId)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO purchase_draft_item (
                        draft_id, product_id, quantity, product_name_snapshot,
                        unit_price_snapshot, line_amount_snapshot
                    ) VALUES ('%s', 980003, 2, 'Bad total', 12.50, 24.99)
                    """.formatted(draftId)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO purchase_draft (
                        draft_id, user_id, action_type, parameter_digest, valid_until
                    ) VALUES (
                        UUID(), 880001, 'REFUND', '%s',
                        DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE)
                    )
                    """.formatted("b".repeat(64))));
        }
    }

    @Test
    void confirmationStoresOnlyOpaqueHashAndEnforcesBindingsAndState() throws SQLException {
        String draftId = UUID.randomUUID().toString();
        String confirmationId = UUID.randomUUID().toString();
        String nonce = UUID.randomUUID().toString();
        String tokenHash = "c".repeat(64);
        String digest = "d".repeat(64);
        try (Connection connection = connection()) {
            insertPurchaseDraft(connection, draftId, 880002, digest);
            insertPurchaseConfirmation(
                    connection,
                    confirmationId,
                    tokenHash,
                    draftId,
                    880002,
                    digest,
                    nonce,
                    "{\"items\":[{\"productId\":980001,\"quantity\":2}]}"
            );

            assertThat(columnNames(connection, "purchase_confirmation"))
                    .contains("token_hash", "parameter_digest", "parameters_json", "nonce")
                    .doesNotContain("token", "confirmation_token", "plaintext_token");
            assertThat(indexColumns(
                    connection,
                    "purchase_confirmation",
                    "uk_purchase_confirmation_token_hash"
            )).isEqualTo("token_hash");
            assertThat(indexColumns(
                    connection,
                    "purchase_confirmation",
                    "idx_purchase_confirmation_user_status_expiry"
            )).isEqualTo("user_id,status,expires_at,confirmation_id");

            assertConstraintViolation(() -> insertPurchaseConfirmation(
                    connection,
                    UUID.randomUUID().toString(),
                    tokenHash,
                    UUID.randomUUID().toString(),
                    880002,
                    digest,
                    UUID.randomUUID().toString(),
                    "{\"items\":[{\"productId\":1,\"quantity\":1}]}"
            ));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    UPDATE purchase_confirmation
                       SET status = 'CONSUMED'
                     WHERE confirmation_id = '%s'
                    """.formatted(confirmationId)));
            executeUpdate(connection, """
                    UPDATE purchase_confirmation
                       SET status = 'CONSUMED', consumed_at = UTC_TIMESTAMP(6), order_id = 'agent-order-1'
                     WHERE confirmation_id = '%s'
                    """.formatted(confirmationId));
            assertThat(singleString(connection, """
                    SELECT status FROM purchase_confirmation
                     WHERE confirmation_id = '%s'
                    """.formatted(confirmationId))).isEqualTo("CONSUMED");
        }
    }

    @Test
    void confirmationRejectsMalformedOrOversizedCanonicalParameters() throws SQLException {
        try (Connection connection = connection()) {
            assertInvalidConfirmationParameters(
                    connection,
                    "{\"items\":[{\"productId\":1,\"quantity\":\"2\"}]}"
            );
            assertInvalidConfirmationParameters(
                    connection,
                    "{\"items\":[{\"productId\":1,\"quantity\":1,\"sql\":\"SELECT 1\"}]}"
            );
            assertInvalidConfirmationParameters(connection, purchaseParametersJson(51));
            assertInvalidConfirmationParameters(connection, "{\"items\":[]}");
        }
    }

    @Test
    void agentConfigurationDraftAcceptsOnlyLowRiskAllowlistedValues() throws SQLException {
        try (Connection connection = connection()) {
            executeUpdate(connection, """
                    INSERT INTO agent_configuration_draft (
                        configuration_draft_id, administrator_id, configuration_key,
                        proposed_value, reason
                    ) VALUES (
                        UUID(), 770001, 'AGENT_RESPONSE_STYLE', JSON_QUOTE('CONCISE'),
                        'Prefer compact answers'
                    )
                    """);
            executeUpdate(connection, """
                    INSERT INTO agent_configuration_draft (
                        configuration_draft_id, administrator_id, configuration_key,
                        proposed_value, reason
                    ) VALUES (
                        UUID(), 770001, 'AGENT_TOOL_RESULT_LIMIT', CAST(25 AS JSON),
                        'Limit low-risk result size'
                    )
                    """);
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO agent_configuration_draft (
                        configuration_draft_id, administrator_id, configuration_key,
                        proposed_value, reason
                    ) VALUES (
                        UUID(), 770001, 'AGENT_CAN_REFUND', JSON_QUOTE('CONCISE'),
                        'Escalate privileges'
                    )
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO agent_configuration_draft (
                        configuration_draft_id, administrator_id, configuration_key,
                        proposed_value, reason, risk_level
                    ) VALUES (
                        UUID(), 770001, 'AGENT_RESPONSE_STYLE', JSON_QUOTE('CONCISE'),
                        'High risk must not be represented', 'HIGH'
                    )
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO agent_configuration_draft (
                        configuration_draft_id, administrator_id, configuration_key,
                        proposed_value, reason
                    ) VALUES (
                        UUID(), 770001, 'AGENT_TOOL_RESULT_LIMIT', CAST(101 AS JSON),
                        'Too many results'
                    )
                    """));
        }
    }

    @Test
    void schemaContainsNoReferentialConstraints() throws SQLException {
        try (Connection connection = connection();
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT COUNT(*)
                     FROM information_schema.referential_constraints
                     WHERE constraint_schema = DATABASE()
                     """);
             ResultSet resultSet = statement.executeQuery()) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getInt(1)).isZero();
        }
    }

    @Test
    void userJourneyTablesEnforceIdempotencyAndTimelineDeduplication() throws SQLException {
        try (Connection connection = connection()) {
            String keyHash = "a".repeat(64);
            String fingerprint = "b".repeat(64);
            executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint, request_id
                    ) VALUES (9001, '%s', '%s', 'request-one')
                    """.formatted(keyHash, fingerprint));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint, request_id
                    ) VALUES (9001, '%s', '%s', 'request-two')
                    """.formatted(keyHash, fingerprint)));

            executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint,
                        order_id, request_id, status
                    ) VALUES (9002, '%s', '%s', 'order-unique-9002',
                              'request-order-one', 'ORDER_CREATED')
                    """.formatted("2".repeat(64), "3".repeat(64)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint,
                        order_id, request_id, status
                    ) VALUES (9003, '%s', '%s', 'order-unique-9002',
                              'request-order-two', 'ORDER_CREATED')
                    """.formatted("4".repeat(64), "5".repeat(64))));

            executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, order_id, event_type, detail_code
                    ) VALUES (9001, 'ORDER', 'order-9001', 'order-9001',
                              'ORDER_CREATED', 'ORDER_DURABLY_CREATED')
                    """);
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, order_id, event_type, detail_code
                    ) VALUES (9001, 'ORDER', 'order-9001', 'order-9001',
                              'ORDER_CREATED', 'DUPLICATE')
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, event_type, detail_code
                    ) VALUES (9001, 'ORDER', 'order-invalid', 'INVENTED', 'INVALID')
                    """));
        }
    }

    @Test
    void userJourneyChecksRejectInvalidIdentityStateAndCorrelationData() throws SQLException {
        try (Connection connection = connection()) {
            String keyHash = "c".repeat(64);
            String fingerprint = "d".repeat(64);
            executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint, request_id
                    ) VALUES (9200, '%s', '%s', 'valid-processing-request')
                    """.formatted(keyHash, fingerprint));
            executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint,
                        order_id, request_id, status
                    ) VALUES (9201, '%s', '%s', 'valid-order-created',
                              'valid-created-request', 'ORDER_CREATED')
                    """.formatted("6".repeat(64), "7".repeat(64)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint
                    ) VALUES (0, '%s', '%s')
                    """.formatted(keyHash, fingerprint)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint, order_id, status
                    ) VALUES (9101, '%s', '%s', 'order-processing', 'PROCESSING')
                    """.formatted("e".repeat(64), fingerprint)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint, status
                    ) VALUES (9102, '%s', '%s', 'ORDER_CREATED')
                    """.formatted("f".repeat(64), fingerprint)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint, request_id
                    ) VALUES (9103, '%s', '%s', 'contains whitespace')
                    """.formatted("1".repeat(64), fingerprint)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint
                    ) VALUES (9104, '%s', '%s')
                    """.formatted("A".repeat(64), fingerprint)));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint
                    ) VALUES (9105, '%s', '%s')
                    """.formatted("8".repeat(64), "B".repeat(64))));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO order_purchase_intent (
                        user_id, idempotency_key_hash, request_fingerprint, status
                    ) VALUES (9106, '%s', '%s', 'INVALID')
                    """.formatted("9".repeat(64), fingerprint)));

            executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, order_id, event_type,
                        request_id, traceparent, detail_code
                    ) VALUES (9200, 'ORDER', 'valid-order-timeline', 'valid-order-timeline',
                              'ORDER_CREATED', 'valid-timeline-request',
                              '00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01',
                              'VALID_ORDER')
                    """);
            executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, reservation_no,
                        event_type, detail_code
                    ) VALUES (9200, 'RESERVATION', 'valid-reservation-timeline',
                              'valid-reservation-timeline', 'RESERVED', 'VALID_RESERVATION')
                    """);

            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, order_id, event_type, detail_code
                    ) VALUES (0, 'ORDER', 'order-zero', 'order-zero',
                              'ORDER_CREATED', 'INVALID_USER')
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, order_id, event_type, detail_code
                    ) VALUES (9101, 'ORDER', 'order-a', 'order-b',
                              'ORDER_CREATED', 'MISMATCHED_ORDER')
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, reservation_no, event_type, detail_code
                    ) VALUES (9101, 'RESERVATION', 'reservation-a', 'reservation-b',
                              'RESERVED', 'MISMATCHED_RESERVATION')
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, reservation_no, event_type, detail_code
                    ) VALUES (9101, 'RESERVATION', 'reservation-created', 'reservation-created',
                              'ORDER_CREATED', 'MISSING_ORDER')
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, order_id, event_type,
                        request_id, detail_code
                    ) VALUES (9101, 'ORDER', 'order-request', 'order-request',
                              'ORDER_CREATED', 'bad request', 'INVALID_REQUEST')
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, order_id, event_type,
                        traceparent, detail_code
                    ) VALUES (9101, 'ORDER', 'order-trace', 'order-trace',
                              'ORDER_CREATED', 'not-a-traceparent', 'INVALID_TRACE')
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, order_id, event_type, detail_code
                    ) VALUES (9101, 'ACCOUNT', 'bad-resource-type', 'bad-resource-type',
                              'ORDER_CREATED', 'INVALID_RESOURCE_TYPE')
                    """));
            assertConstraintViolation(() -> executeUpdate(connection, """
                    INSERT INTO user_transaction_timeline (
                        user_id, resource_type, resource_id, order_id, event_type, detail_code
                    ) VALUES (9101, 'ORDER', 'bad-event-type', 'bad-event-type',
                              'INVENTED', 'INVALID_EVENT_TYPE')
                    """));
            for (String invalidTraceparent : List.of(
                    "00-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA-bbbbbbbbbbbbbbbb-01",
                    "ff-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-bbbbbbbbbbbbbbbb-01",
                    "00-00000000000000000000000000000000-bbbbbbbbbbbbbbbb-01",
                    "00-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa-0000000000000000-01"
            )) {
                assertConstraintViolation(() -> executeUpdate(connection, """
                        INSERT INTO user_transaction_timeline (
                            user_id, resource_type, resource_id, order_id, event_type,
                            traceparent, detail_code
                        ) VALUES (9101, 'ORDER', 'invalid-trace-%s', 'invalid-trace-%s',
                                  'ORDER_CREATED', '%s', 'INVALID_TRACE')
                        """.formatted(
                                Integer.toUnsignedString(invalidTraceparent.hashCode()),
                                Integer.toUnsignedString(invalidTraceparent.hashCode()),
                                invalidTraceparent
                        )));
            }
        }
    }

    @Test
    void userJourneyConstraintNamesAndStreamingIndexOrderAreStable() throws SQLException {
        try (Connection connection = connection()) {
            assertThat(checkConstraintNames(connection, "order_purchase_intent"))
                    .containsExactlyInAnyOrder(
                            "ck_order_purchase_intent_user_id",
                            "ck_order_purchase_intent_key_hash",
                            "ck_order_purchase_intent_fingerprint",
                            "ck_order_purchase_intent_status",
                            "ck_order_purchase_intent_state",
                            "ck_order_purchase_intent_request_id"
                    );
            assertThat(checkConstraintNames(connection, "user_transaction_timeline"))
                    .containsExactlyInAnyOrder(
                            "ck_user_timeline_user_id",
                            "ck_user_timeline_resource_type",
                            "ck_user_timeline_event_type",
                            "ck_user_timeline_resource_identity",
                            "ck_user_timeline_order_created",
                            "ck_user_timeline_request_id",
                            "ck_user_timeline_traceparent"
                    );
            assertThat(indexDefinition(
                    connection,
                    "order_purchase_intent",
                    "uk_order_purchase_intent_user_key"
            )).isEqualTo(new IndexDefinition("user_id,idempotency_key_hash", false));
            assertThat(indexDefinition(
                    connection,
                    "order_purchase_intent",
                    "uk_order_purchase_intent_order"
            )).isEqualTo(new IndexDefinition("order_id", false));
            assertThat(indexDefinition(
                    connection,
                    "user_transaction_timeline",
                    "uk_user_timeline_fact"
            )).isEqualTo(new IndexDefinition("resource_type,resource_id,event_type", false));
            assertThat(indexDefinition(
                    connection,
                    "user_transaction_timeline",
                    "idx_user_timeline_resource_stream"
            )).isEqualTo(new IndexDefinition(
                    "user_id,resource_type,resource_id,event_id", true
            ));
        }
    }

    @Test
    void auditLogIsAppendOnlyAtTheDatabaseLayer() throws SQLException {
        try (Connection connection = connection()) {
            long auditId = insertAuditLog(connection);

            assertThatThrownBy(() -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE audit_log SET result = 'FAILURE' WHERE audit_id = ?"
                )) {
                    statement.setLong(1, auditId);
                    statement.executeUpdate();
                }
            }).isInstanceOf(SQLException.class)
                    .hasMessageContaining("audit_log is append-only");

            assertThatThrownBy(() -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM audit_log WHERE audit_id = ?"
                )) {
                    statement.setLong(1, auditId);
                    statement.executeUpdate();
                }
            }).isInstanceOf(SQLException.class)
                    .hasMessageContaining("audit_log is append-only");

            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT result, source FROM audit_log WHERE audit_id = ?"
            )) {
                statement.setLong(1, auditId);
                try (ResultSet resultSet = statement.executeQuery()) {
                    assertThat(resultSet.next()).isTrue();
                    assertThat(resultSet.getString("result")).isEqualTo("SUCCESS");
                    assertThat(resultSet.getString("source")).isEqualTo("ADMIN_API");
                }
            }
        }
    }

    @Test
    void applicationReferenceGuardRejectsMissingParentBeforeWrite() throws SQLException {
        try (Connection connection = connection()) {
            long missingUserId = 9_999_999L;

            assertThat(activeRecordExists(connection, "app_user", "user_id", missingUserId)).isFalse();
            assertThatThrownBy(() -> requireActiveRecord(connection, "app_user", "user_id", missingUserId))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("app_user");

            executeUpdate(connection, """
                    INSERT INTO sales_order (order_id, user_id, total_amount, status)
                    VALUES ('unchecked-orphan', 9999999, 0.00, 'PENDING')
                    """);
            assertThat(rowExists(connection, "sales_order", "order_id", "unchecked-orphan")).isTrue();
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private static void insertPurchaseDraft(
            Connection connection,
            String draftId,
            long userId,
            String digest
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO purchase_draft (
                    draft_id, user_id, action_type, parameter_digest, valid_until
                ) VALUES (?, ?, 'CREATE_ORDER', ?, DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 5 MINUTE))
                """)) {
            statement.setString(1, draftId);
            statement.setLong(2, userId);
            statement.setString(3, digest);
            statement.executeUpdate();
        }
    }

    private static void insertPurchaseConfirmation(
            Connection connection,
            String confirmationId,
            String tokenHash,
            String draftId,
            long userId,
            String digest,
            String nonce,
            String parametersJson
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO purchase_confirmation (
                    confirmation_id, token_hash, draft_id, user_id, action_type,
                    parameter_digest, parameters_json, nonce, expires_at
                ) VALUES (?, ?, ?, ?, 'CREATE_ORDER', ?, ?, ?,
                          DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 2 MINUTE))
                """)) {
            statement.setString(1, confirmationId);
            statement.setString(2, tokenHash);
            statement.setString(3, draftId);
            statement.setLong(4, userId);
            statement.setString(5, digest);
            statement.setString(6, parametersJson);
            statement.setString(7, nonce);
            statement.executeUpdate();
        }
    }

    private static void assertInvalidConfirmationParameters(Connection connection, String parametersJson)
            throws SQLException {
        String draftId = UUID.randomUUID().toString();
        String digest = UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", "");
        insertPurchaseDraft(connection, draftId, 880003, digest);
        assertConstraintViolation(() -> insertPurchaseConfirmation(
                connection,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString().replace("-", "")
                        + UUID.randomUUID().toString().replace("-", ""),
                draftId,
                880003,
                digest,
                UUID.randomUUID().toString(),
                parametersJson
        ));
    }

    private static String purchaseParametersJson(int itemCount) {
        StringBuilder json = new StringBuilder("{\"items\":[");
        for (int index = 1; index <= itemCount; index++) {
            if (index > 1) {
                json.append(',');
            }
            json.append("{\"productId\":")
                    .append(index)
                    .append(",\"quantity\":1}");
        }
        return json.append("]}").toString();
    }

    private static int singleInt(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String singleString(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }

    private static List<String> columnNames(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ?
                 ORDER BY ordinal_position
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                var columns = new java.util.ArrayList<String>();
                while (resultSet.next()) {
                    columns.add(resultSet.getString(1));
                }
                return columns;
            }
        }
    }

    private static void insertUser(Connection connection, String username, String email) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO app_user (username, password_hash, email, role)
                VALUES (?, 'not-a-real-password-hash', ?, 'ROLE_USER')
                """)) {
            statement.setString(1, username);
            statement.setString(2, email);
            statement.executeUpdate();
        }
    }

    private static void insertReservation(
            Connection connection, String reservationNo, long activityId, long userId, String status
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO sale_reservation (
                    reservation_no, activity_id, user_id, product_id, quantity,
                    reserved_amount, status, expires_at
                ) VALUES (?, ?, ?, 900001, 1, 10.00, ?, '2030-01-01 00:00:00')
                """)) {
            statement.setString(1, reservationNo);
            statement.setLong(2, activityId);
            statement.setLong(3, userId);
            statement.setString(4, status);
            statement.executeUpdate();
        }
    }

    private static void insertPayment(Connection connection, String paymentNo, String orderId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO payment_order (payment_no, order_id, amount, status)
                VALUES (?, ?, 10.00, 'PENDING')
                """)) {
            statement.setString(1, paymentNo);
            statement.setString(2, orderId);
            statement.executeUpdate();
        }
    }

    private static void insertProcessedEvent(Connection connection, String consumer, String eventId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO processed_event (consumer_name, event_id, event_type)
                VALUES (?, ?, 'OrderCreated')
                """)) {
            statement.setString(1, consumer);
            statement.setString(2, eventId);
            statement.executeUpdate();
        }
    }

    private static void insertCallback(Connection connection, String callbackId, String payloadHash,
            String paymentNo) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            INSERT INTO payment_callback_ledger(callback_id,payload_hash,provider,payment_no,
              provider_transaction_no,outcome,amount,currency,occurred_at,receive_result,business_result)
            VALUES(?,?,'MOCK',?,'MOCK-TXN-0123456789abcdef0123456789abcdef',
              'SUCCEEDED',1.00,'CNY',UTC_TIMESTAMP(6),'ACCEPTED','IDEMPOTENT')
            """)) {
            statement.setString(1, callbackId);
            statement.setString(2, payloadHash);
            statement.setString(3, paymentNo);
            statement.executeUpdate();
        }
    }

    private static void insertNonce(Connection connection, String nonceHash, String callbackId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO payment_callback_nonce(nonce_hash,callback_id) VALUES(?,?)")) {
            statement.setString(1, nonceHash);
            statement.setString(2, callbackId);
            statement.executeUpdate();
        }
    }

    private static void insertOutbox(Connection connection, String eventId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO outbox_event(event_id, aggregate_type, aggregate_id, event_type, payload)
                VALUES (?, 'ORDER', 'constraint-order', 'ORDER_CREATED', JSON_OBJECT('schemaVersion', 1))
                """)) {
            statement.setString(1, eventId);
            statement.executeUpdate();
        }
    }

    private static IndexDefinition indexDefinition(
            Connection connection,
            String table,
            String index
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ','),
                       MAX(non_unique)
                  FROM information_schema.statistics
                 WHERE table_schema = DATABASE() AND table_name = ? AND index_name = ?
                """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet resultSet = statement.executeQuery()) {
                assertThat(resultSet.next()).isTrue();
                return new IndexDefinition(resultSet.getString(1), resultSet.getInt(2) == 1);
            }
        }
    }

    private static String indexColumns(Connection connection, String table, String index)
            throws SQLException {
        return indexDefinition(connection, table, index).columns();
    }

    private record IndexDefinition(String columns, boolean nonUnique) { }

    private static List<String> checkConstraintNames(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT constraint_name
                  FROM information_schema.table_constraints
                 WHERE constraint_schema = DATABASE()
                   AND table_name = ?
                   AND constraint_type = 'CHECK'
                """)) {
            statement.setString(1, table);
            try (ResultSet resultSet = statement.executeQuery()) {
                var names = new java.util.ArrayList<String>();
                while (resultSet.next()) {
                    names.add(resultSet.getString(1));
                }
                return names;
            }
        }
    }

    private static void insertSeckillProcessing(
            Connection connection,
            String eventId,
            String streamKey,
            String streamEntryId,
            String status
    ) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO seckill_event_processing (
                    event_id, stream_key, stream_entry_id, payload_hash, status
                ) VALUES (?, ?, ?, ?, ?)
                """)) {
            statement.setString(1, eventId);
            statement.setString(2, streamKey);
            statement.setString(3, streamEntryId);
            statement.setString(4, "d".repeat(64));
            statement.setString(5, status);
            statement.executeUpdate();
        }
    }

    private static long insertRefreshToken(Connection connection, String tokenHash, Long parentTokenId)
            throws SQLException {
        long tokenId = REFRESH_TOKEN_IDS.getAndIncrement();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO refresh_token (
                    refresh_token_id, token_hash, csrf_hash, family_id, user_id,
                    session_type, parent_token_id,
                    issuer, audience, expires_at
                ) VALUES (
                    ?, ?, 'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
                    '00000000-0000-0000-0000-000000000001', 1, 'USER', ?, 'hotshop', 'hotshop',
                    DATE_ADD(CURRENT_TIMESTAMP(6), INTERVAL 1 DAY)
                )
                """)) {
            statement.setLong(1, tokenId);
            statement.setString(2, tokenHash);
            if (parentTokenId == null) {
                statement.setNull(3, java.sql.Types.BIGINT);
            } else {
                statement.setLong(3, parentTokenId);
            }
            statement.executeUpdate();
            return tokenId;
        }
    }

    private static long insertAuditLog(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                """
                INSERT INTO audit_log (
                    actor_type, actor_id, action, resource_type, resource_id,
                    result, request_id, trace_id, source, state_summary
                ) VALUES (
                    'ADMIN', '42', 'CATALOG_PRODUCT_UPDATED', 'CATALOG_PRODUCT', '7',
                    'SUCCESS', 'append-only-test',
                    '0123456789abcdef0123456789abcdef', 'ADMIN_API',
                    JSON_OBJECT('changedFields', JSON_ARRAY('stock'))
                )
                """,
                Statement.RETURN_GENERATED_KEYS
        )) {
            statement.executeUpdate();
            try (ResultSet generatedKeys = statement.getGeneratedKeys()) {
                assertThat(generatedKeys.next()).isTrue();
                return generatedKeys.getLong(1);
            }
        }
    }

    private static Callable<Boolean> rotationAttempt(
            CountDownLatch ready, CountDownLatch start, long parentTokenId, String tokenHash
    ) {
        return () -> {
            try (Connection connection = connection()) {
                connection.setAutoCommit(false);
                ready.countDown();
                if (!start.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to start concurrent rotation");
                }
                try {
                    insertRefreshToken(connection, tokenHash, parentTokenId);
                    connection.commit();
                    return true;
                } catch (SQLException error) {
                    connection.rollback();
                    if (!isConstraintViolation(error)) {
                        throw error;
                    }
                    return false;
                }
            }
        };
    }

    private static boolean activeRecordExists(
            Connection connection, String table, String idColumn, long id
    ) throws SQLException {
        String sql = "SELECT EXISTS(SELECT 1 FROM " + table + " WHERE " + idColumn
                + " = ? AND deleted_at IS NULL AND status = 'ACTIVE')";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private static void requireActiveRecord(
            Connection connection, String table, String idColumn, long id
    ) throws SQLException {
        if (!activeRecordExists(connection, table, idColumn, id)) {
            throw new IllegalArgumentException("Missing active reference in " + table + ": " + id);
        }
    }

    private static boolean rowExists(Connection connection, String table, String idColumn, String id)
            throws SQLException {
        String sql = "SELECT EXISTS(SELECT 1 FROM " + table + " WHERE " + idColumn + " = ?)";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                return resultSet.next() && resultSet.getBoolean(1);
            }
        }
    }

    private static int executeUpdate(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static void assertConstraintViolation(SqlOperation operation) {
        assertThatExceptionOfType(SQLException.class)
                .isThrownBy(operation::run)
                .satisfies(error -> assertThat(isConstraintViolation(error))
                        .as("database rejected the row with a unique/check constraint")
                        .isTrue());
    }

    private static boolean isConstraintViolation(SQLException error) {
        return error.getSQLState().startsWith("23") || error.getErrorCode() == 3819;
    }

    @FunctionalInterface
    private interface SqlOperation {
        void run() throws SQLException;
    }
}
