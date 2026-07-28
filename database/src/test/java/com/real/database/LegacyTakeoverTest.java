package com.real.database;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class LegacyTakeoverTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_legacy")
            .withUsername("hotshop")
            .withPassword("hotshop-test")
            .withInitScript("legacy/legacy-schema.sql");

    @Test
    void explicitVersionZeroBaselineImportsAndRemovesLegacyTables() throws Exception {
        try (Connection connection = DriverManager.getConnection(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        ); Statement statement = connection.createStatement()) {
            statement.execute("CREATE PROCEDURE delete_and_reset() SELECT 1");
        }

        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .baselineVersion("0")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();

        flyway.baseline();
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
        }
    }

    private static int singleInt(Statement statement, String sql) throws Exception {
        try (ResultSet resultSet = statement.executeQuery(sql)) {
            assertThat(resultSet.next()).isTrue();
            return resultSet.getInt(1);
        }
    }
}
