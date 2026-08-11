package com.real.database;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class V15ToV16MigrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_v15_upgrade")
            .withUsername("hotshop").withPassword("hotshop-test")
            .withCommand("--log-bin-trust-function-creators=1");

    @Test
    void populatedV15DatabaseUpgradesInPlaceToV16() throws Exception {
        Flyway v15 = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").target(MigrationVersion.fromVersion("1.5")).load();
        assertThat(v15.migrate().targetSchemaVersion).isEqualTo("1.5");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement()) {
            statement.executeUpdate("""
                INSERT INTO sales_order(order_id,user_id,total_amount,currency,status,expires_at)
                VALUES('v15-order',7,8.50,'CNY','PENDING',DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 10 MINUTE))
                """);
            statement.executeUpdate("""
                INSERT INTO payment_order(payment_no,order_id,amount,currency,status,expires_at)
                SELECT 'v15-payment',order_id,total_amount,currency,'PENDING',expires_at
                  FROM sales_order WHERE order_id='v15-order'
                """);
        }
        Flyway latest = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load();
        assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("1.8");
        try (var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
             var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM payment_order WHERE payment_no='v15-payment'")) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isOne();
        }
    }
}
