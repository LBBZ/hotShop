package com.real.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegacyTakeoverTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_legacy")
            .withUsername("hotshop")
            .withPassword("hotshop-test")
            .withCommand("--log-bin-trust-function-creators=1")
            .withInitScript("legacy/legacy-schema.sql");

    @Test
    void explicitVersionZeroBaselineImportsAndRemovesLegacyTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE PROCEDURE delete_and_reset() SELECT 1");
        }

        Flyway throughV14 = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineVersion("0")
                .target("1.4")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();

        throughV14.baseline();
        assertThat(throughV14.migrate().migrationsExecuted).isEqualTo(5);
        String abandonedEvent = UUID.randomUUID().toString();
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO outbox_event(event_id,aggregate_type,aggregate_id,event_type,payload,
                                         status,publish_attempts,last_error)
                VALUES(?,'ORDER','legacy-order','ORDER_CREATED',JSON_OBJECT('schemaVersion',1),
                       'PUBLISHING',3,'old publisher disappeared')
                """)) {
            statement.setString(1, abandonedEvent);
            statement.executeUpdate();
        }

        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); Statement statement = connection.createStatement()) {
            assertThat(singleInt(statement, "SELECT COUNT(*) FROM app_user WHERE username = 'legacy-admin'"))
                    .isEqualTo(1);
            assertThat(singleInt(statement, "SELECT COUNT(*) FROM catalog_product WHERE sku = 'LEGACY-1'"))
                    .isEqualTo(1);
            assertThat(singleInt(statement, "SELECT COUNT(*) FROM sales_order WHERE order_id = 'legacy-order'"))
                    .isEqualTo(1);
            assertThat(singleInt(statement, "SELECT COUNT(*) FROM sales_order_item WHERE order_id = 'legacy-order'"))
                    .isEqualTo(1);
            assertThat(singleInt(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.tables
                    WHERE table_schema = DATABASE()
                      AND table_name IN ('user', 'product', 'order', 'order_item')
                    """)).isZero();
            assertThat(singleInt(statement, """
                    SELECT COUNT(*)
                    FROM information_schema.routines
                    WHERE routine_schema = DATABASE() AND routine_name = 'delete_and_reset'
                    """)).isZero();
            assertThat(singleInt(statement, """
                    SELECT COUNT(*) FROM information_schema.columns
                     WHERE table_schema=DATABASE() AND table_name='outbox_event'
                       AND column_name IN ('lease_token','lease_expires_at','consecutive_attempts',
                                           'manual_replay_count','failure_category','version')
                    """)).isEqualTo(6);
            assertThat(singleInt(statement, """
                    SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                     WHERE table_schema=DATABASE() AND table_name='outbox_event'
                       AND index_name IN ('idx_outbox_claim','idx_outbox_failed_cursor','uk_outbox_event_id')
                    """)).isEqualTo(3);
            assertThat(singleString(statement, """
                    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                      FROM information_schema.statistics
                     WHERE table_schema=DATABASE() AND table_name='outbox_event'
                       AND index_name='idx_outbox_claim'
                    """)).isEqualTo("status,available_at,lease_expires_at,outbox_id");
            assertThat(singleString(statement, """
                    SELECT GROUP_CONCAT(column_name ORDER BY seq_in_index SEPARATOR ',')
                      FROM information_schema.statistics
                     WHERE table_schema=DATABASE() AND table_name='outbox_event'
                       AND index_name='idx_outbox_failed_cursor'
                    """)).isEqualTo("status,outbox_id");
            assertThat(singleInt(statement, """
                    SELECT COUNT(*) FROM information_schema.table_constraints
                     WHERE constraint_schema=DATABASE() AND table_name='outbox_event'
                       AND constraint_type='CHECK'
                       AND constraint_name IN ('ck_outbox_manual_replay_count',
                         'ck_outbox_consecutive_attempts','ck_outbox_version','ck_outbox_lease_pair')
                    """)).isEqualTo(4);
            assertThat(singleInt(statement, """
                    SELECT COUNT(*) FROM information_schema.referential_constraints
                     WHERE constraint_schema=DATABASE()
                    """)).isZero();
            assertThat(singleInt(statement, """
                    SELECT COUNT(*) FROM outbox_event
                     WHERE event_id='%s' AND status='NEW' AND publish_attempts=3
                       AND consecutive_attempts=0 AND lease_token IS NULL
                       AND lease_expires_at IS NULL AND version=1
                       AND failure_category='LEGACY_PUBLISHING_RECOVERED'
                       AND last_error='LEGACY_PUBLISHING_RECOVERED'
                    """.formatted(abandonedEvent))).isOne();
        }
    }

    private static int singleInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }

    private static String singleString(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getString(1);
        }
    }
}
