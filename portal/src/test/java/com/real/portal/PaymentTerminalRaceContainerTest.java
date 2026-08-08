package com.real.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.dto.MockPaymentCallbackResponse;
import com.real.domain.payment.MockPaymentProperties;
import com.real.domain.payment.MockPaymentProvider;
import com.real.portal.payment.MockPaymentCallbackService;
import com.real.portal.payment.PaymentCallbackAuditService;
import com.real.security.audit.AuditSensitiveDataSanitizer;
import com.real.security.audit.JdbcAuditLogWriter;
import com.real.task.timeoutOrderTask.OrderTimeoutService;
import jakarta.servlet.http.HttpServletRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
class PaymentTerminalRaceContainerTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_payment_race").withUsername("hotshop").withPassword("hotshop-test")
            .withCommand("--log-bin-trust-function-creators=1");

    static JdbcTemplate jdbc;
    static DataSourceTransactionManager transactionManager;
    static TransactionTemplate tx;
    static ObjectMapper json;
    static MockPaymentProvider provider;
    static MockPaymentCallbackService callbacks;
    static OrderTimeoutService timeouts;

    @BeforeAll
    static void setup() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transactionManager = new DataSourceTransactionManager(dataSource);
        tx = new TransactionTemplate(transactionManager);
        json = new ObjectMapper().findAndRegisterModules();

        MockPaymentProperties properties = new MockPaymentProperties();
        properties.setEnabled(true);
        properties.setSecret(UUID.randomUUID().toString());
        provider = new MockPaymentProvider(properties);
        JdbcAuditLogWriter writer = new JdbcAuditLogWriter(
                jdbc, json, new AuditSensitiveDataSanitizer(json));
        callbacks = new MockPaymentCallbackService(jdbc, json, provider, properties,
                new PaymentCallbackAuditService(writer), tx);

        ProxyFactory proxy = new ProxyFactory(new OrderTimeoutService(jdbc, json));
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(
                transactionManager, new AnnotationTransactionAttributeSource()));
        timeouts = (OrderTimeoutService) proxy.getProxy();
    }

    @AfterAll
    static void containerOwnsDatabaseLifecycle() {
        // Testcontainers closes the database after the class.
    }

    @Test
    void paymentWinnerMakesLaterTimeoutAnInventoryNoOpAndDuplicateCallbackIsIdempotent() throws Exception {
        Facts facts = facts();
        assertThat(accept(facts, nonce()).result()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(accept(facts, nonce()).result()).isEqualTo("IDEMPOTENT");
        assertThat(timeouts.process(facts.timeout())).isEqualTo(OrderTimeoutService.ProcessResult.TERMINAL_NOOP);
        assertTerminalEvidence(facts, "PAID", "SUCCEEDED", 9);
    }

    @Test
    void timeoutWinnerProducesExplicitLateSuccess() throws Exception {
        Facts facts = facts();
        assertThat(timeouts.process(facts.timeout())).isEqualTo(OrderTimeoutService.ProcessResult.CANCELED);
        assertThat(accept(facts, nonce()).result()).isEqualTo("PAYMENT_LATE_SUCCEEDED");
        assertThat(accept(facts, nonce()).result()).isEqualTo("IDEMPOTENT");
        assertThat(timeouts.process(facts.timeout())).isEqualTo(OrderTimeoutService.ProcessResult.DUPLICATE);
        assertTerminalEvidence(facts, "CANCELED", "LATE_SUCCEEDED", 10);
    }

    @Test
    void twentyRealProductionServiceRacesHaveOnlyTheTwoLegalTerminalMatrices() throws Exception {
        for (int round = 0; round < 20; round++) {
            Facts facts = facts();
            CountDownLatch ready = new CountDownLatch(2);
            CountDownLatch start = new CountDownLatch(1);
            try (var executor = Executors.newFixedThreadPool(2)) {
                var payment = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return accept(facts, nonce());
                });
                var timeout = executor.submit(() -> {
                    ready.countDown();
                    start.await();
                    return timeouts.process(facts.timeout());
                });
                assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
                start.countDown();
                MockPaymentCallbackResponse paymentResult = payment.get(20, TimeUnit.SECONDS);
                OrderTimeoutService.ProcessResult timeoutResult = timeout.get(20, TimeUnit.SECONDS);
                assertThat(paymentResult.result()).isIn("PAYMENT_SUCCEEDED", "PAYMENT_LATE_SUCCEEDED");
                assertThat(timeoutResult).isIn(
                        OrderTimeoutService.ProcessResult.CANCELED,
                        OrderTimeoutService.ProcessResult.TERMINAL_NOOP);
            }

            assertThat(accept(facts, nonce()).result()).isEqualTo("IDEMPOTENT");
            String order = value("SELECT status FROM sales_order WHERE order_id=?", facts.orderId());
            String payment = value("SELECT status FROM payment_order WHERE payment_no=?", facts.paymentNo());
            int stock = count("SELECT stock FROM catalog_product WHERE product_id=?", facts.productId());
            assertThat(order + "/" + payment + "/" + stock)
                    .isIn("PAID/SUCCEEDED/9", "CANCELED/LATE_SUCCEEDED/10");
            assertTerminalEvidence(facts, order, payment, stock);
        }
    }

    private static void assertTerminalEvidence(Facts facts, String order, String payment, int stock) {
        assertThat(value("SELECT status FROM sales_order WHERE order_id=?", facts.orderId())).isEqualTo(order);
        assertThat(value("SELECT status FROM payment_order WHERE payment_no=?", facts.paymentNo())).isEqualTo(payment);
        assertThat(count("SELECT stock FROM catalog_product WHERE product_id=?", facts.productId())).isEqualTo(stock);
        assertThat(count("SELECT COUNT(*) FROM payment_callback_ledger WHERE callback_id=?", facts.callbackId())).isOne();
        assertThat(count("SELECT COUNT(*) FROM payment_callback_nonce WHERE callback_id=?", facts.callbackId())).isEqualTo(2);
        assertThat(count("SELECT COUNT(*) FROM processed_event WHERE event_id=?", facts.timeout().eventId())).isOne();
        assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='MOCK_PAYMENT_CALLBACK_ACCEPTED' AND resource_id=?",
                facts.paymentNo())).isEqualTo(2);

        int succeeded = count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='PAYMENT_SUCCEEDED'",
                facts.paymentNo());
        int late = count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='PAYMENT_LATE_SUCCEEDED'",
                facts.paymentNo());
        assertThat(succeeded + late).isOne();
        if ("PAID".equals(order)) {
            assertThat(payment).isEqualTo("SUCCEEDED");
            assertThat(stock).isEqualTo(9);
            assertThat(succeeded).isOne();
            assertThat(late).isZero();
            assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='ORDER_CANCELED'",
                    facts.orderId())).isZero();
            assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?",
                    facts.orderId())).isZero();
        } else {
            assertThat(order).isEqualTo("CANCELED");
            assertThat(payment).isEqualTo("LATE_SUCCEEDED");
            assertThat(stock).isEqualTo(10);
            assertThat(succeeded).isZero();
            assertThat(late).isOne();
            assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='ORDER_CANCELED'",
                    facts.orderId())).isOne();
            assertThat(count("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_COMPENSATED' AND resource_id=?",
                    facts.orderId())).isOne();
        }
    }

    private static Facts facts() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO catalog_product(sku,name,price,stock,status) VALUES(?,?,10.00,9,'ACTIVE')",
                "RACE-" + suffix, "race");
        long productId = jdbc.queryForObject(
                "SELECT product_id FROM catalog_product WHERE sku=?", Long.class, "RACE-" + suffix);
        String orderId = "ord_" + suffix;
        jdbc.update("""
            INSERT INTO sales_order(order_id,user_id,total_amount,currency,status,expires_at)
            VALUES(?,77,10.00,'CNY','PENDING',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND))
            """, orderId);
        jdbc.update("""
            INSERT INTO sales_order_item(order_id,product_id,quantity,price,line_amount)
            VALUES(?,?,1,10.00,10.00)
            """, orderId, productId);
        String paymentNo = "MOCK_" + suffix;
        jdbc.update("""
            INSERT INTO payment_order(payment_no,order_id,provider,amount,currency,status,expires_at)
            SELECT ?,order_id,'MOCK',total_amount,currency,'PENDING',expires_at
              FROM sales_order WHERE order_id=?
            """, paymentNo, orderId);

        String callbackId = UUID.randomUUID().toString();
        Map<String, Object> callback = new LinkedHashMap<>();
        callback.put("callbackId", callbackId);
        callback.put("paymentNo", paymentNo);
        callback.put("providerTransactionNo", "MOCK-TXN-" + UUID.randomUUID().toString().replace("-", ""));
        callback.put("outcome", "SUCCEEDED");
        callback.put("amount", "10.00");
        callback.put("currency", "CNY");
        callback.put("occurredAt", Instant.now().toString());
        byte[] body = json.writeValueAsBytes(callback);

        long expiryMs = jdbc.queryForObject("""
            SELECT TIMESTAMPDIFF(MICROSECOND,'1970-01-01 00:00:00',expires_at) DIV 1000
              FROM sales_order WHERE order_id=?
            """, Long.class, orderId);
        var timeout = new OrderTimeoutService.TimeoutEvent(UUID.randomUUID().toString(),
                "LEGACY_ORDER_TIMEOUT_REQUESTED", "ORDER", orderId, orderId, 77,
                new BigDecimal("10.00"), "CNY", expiryMs, 0, Instant.now());
        return new Facts(orderId, paymentNo, productId, callbackId, body, timeout);
    }

    private static MockPaymentCallbackResponse accept(Facts facts, String nonce) {
        String timestamp = Long.toString(Instant.now().getEpochSecond());
        String signature = provider.sign(timestamp, nonce, facts.body());
        return callbacks.accept(timestamp, nonce, signature, facts.body(), request());
    }
    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(com.real.common.api.RequestContext.REQUEST_ID_ATTRIBUTE, UUID.randomUUID().toString());
        request.setAttribute(com.real.common.api.RequestContext.TRACE_ID_ATTRIBUTE, "a".repeat(32));
        return request;
    }
    private static String nonce() { return UUID.randomUUID().toString().replace("-", ""); }
    private static int count(String sql, Object... args) {
        Integer value = jdbc.queryForObject(sql, Integer.class, args);
        return value == null ? 0 : value;
    }
    private static String value(String sql, Object... args) {
        return jdbc.queryForObject(sql, String.class, args);
    }

    private record Facts(String orderId, String paymentNo, long productId, String callbackId,
                         byte[] body, OrderTimeoutService.TimeoutEvent timeout) { }
}
