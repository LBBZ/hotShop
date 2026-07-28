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
            .withPassword("hotshop-test");

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
        assertThat(initialMigrationCount).isEqualTo(3);
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1.2");
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
