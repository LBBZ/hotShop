package com.real.portal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.api.dto.MockPaymentActionRequest;
import com.real.common.api.dto.PaymentResponse;
import com.real.common.audit.AuditEvent;
import com.real.domain.payment.MockPaymentProperties;
import com.real.domain.payment.MockPaymentProvider;
import com.real.portal.payment.*;
import com.real.security.audit.AuditLogWriter;
import com.real.task.timeoutOrderTask.OrderTimeoutService;
import jakarta.servlet.http.HttpServletRequest;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.*;

@Testcontainers
class MockPaymentIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_payment").withUsername("hotshop").withPassword("hotshop-test")
            .withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static TransactionTemplate tx;
    static PaymentService payments;
    static MockPaymentCallbackService callbacks;
    static MockPaymentProvider provider;
    static MockPaymentProperties properties;
    static ObjectMapper json;
    static RecordingAuditWriter auditWriter;

    @BeforeAll
    static void setup() {
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration").load().migrate();
        var dataSource = new org.springframework.jdbc.datasource.DriverManagerDataSource(
                MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        json = new ObjectMapper().findAndRegisterModules();
        properties = new MockPaymentProperties();
        properties.setEnabled(true);
        properties.setSecret(UUID.randomUUID().toString());
        provider = new MockPaymentProvider(properties);
        auditWriter = new RecordingAuditWriter(jdbc, tx, json);
        var audit = new PaymentCallbackAuditService(auditWriter);
        callbacks = new MockPaymentCallbackService(jdbc, json, provider, properties, audit, tx);
        payments = new PaymentService(jdbc, json, properties);
    }

    @Test
    void concurrentCreateIsUniqueOwnedAndUsesDatabaseFacts() throws Exception {
        String orderId = order(101, "12.34", "PENDING");
        CountDownLatch ready = new CountDownLatch(8);
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 8).mapToObj(i -> executor.submit(() -> {
                ready.countDown(); start.await();
                return tx.execute(ignored -> payments.create(101, orderId));
            })).toList();
            assertThat(ready.await(10, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(futures.stream().map(f -> get(f).paymentNo()).distinct().count()).isOne();
        }
        assertThat(count("SELECT COUNT(*) FROM payment_order WHERE order_id=?", orderId)).isOne();
        PaymentResponse response = payments.get(101, paymentNo(orderId));
        assertThat(response.amount()).isEqualByComparingTo("12.34");
        assertThat(response.currency()).isEqualTo("CNY");
        assertThatThrownBy(() -> payments.get(202, response.paymentNo())).hasMessageContaining("Payment was not found");
    }

    @Test
    void actionPersistsDelayAndNeverAcceptsClientBusinessFacts() {
        String orderId = order(102, "22.50", "PENDING");
        PaymentResponse payment = tx.execute(ignored -> payments.create(102, orderId));
        var action = tx.execute(ignored -> payments.action(102, payment.paymentNo(),
                new MockPaymentActionRequest("SUCCEEDED", Duration.ofMinutes(3), 4)));
        Map<String, Object> row = jdbc.queryForMap("""
            SELECT event_type,aggregate_id,JSON_UNQUOTE(JSON_EXTRACT(payload,'$.callback.amount')) amount,
                   JSON_EXTRACT(payload,'$.duplicateCount') duplicates,
                   TIMESTAMPDIFF(SECOND,UTC_TIMESTAMP(6),available_at) delay_seconds
              FROM outbox_event WHERE aggregate_id=? AND event_type='MOCK_PAYMENT_CALLBACK_REQUESTED'
            """, payment.paymentNo());
        assertThat(row.get("amount").toString()).isEqualTo("22.50");
        assertThat(Integer.parseInt(row.get("duplicates").toString())).isEqualTo(4);
        assertThat(((Number) row.get("delay_seconds")).longValue()).isBetween(175L, 180L);
        assertThat(action.localDemoOnly()).isTrue();
    }

    @Test
    void terminalOrdersRejectRepeatedPaymentCreationEvenWhenAPaymentAlreadyExists() {
        String paidOrder = order(109, "19.00", "PENDING");
        tx.executeWithoutResult(ignored -> payments.create(109, paidOrder));
        jdbc.update("UPDATE sales_order SET status='PAID' WHERE order_id=?", paidOrder);
        assertThatThrownBy(() -> tx.execute(ignored -> payments.create(109, paidOrder)))
                .hasMessageContaining("Paid or canceled Orders");

        String canceledOrder = order(110, "20.00", "PENDING");
        tx.executeWithoutResult(ignored -> payments.create(110, canceledOrder));
        jdbc.update("UPDATE sales_order SET status='CANCELED' WHERE order_id=?", canceledOrder);
        assertThatThrownBy(() -> tx.execute(ignored -> payments.create(110, canceledOrder)))
                .hasMessageContaining("Paid or canceled Orders");
    }

    @Test
    void signedSuccessIsIdempotentAndBodyNonceTimestampTamperingIsRejected() throws Exception {
        String orderId = order(103, "30.00", "PENDING");
        String paymentNo = tx.execute(ignored -> payments.create(103, orderId)).paymentNo();
        byte[] body = body(UUID.randomUUID().toString(), paymentNo, "SUCCEEDED", "30.00");
        String nonce = nonce();
        var first = accept(body, nonce, now());
        assertThat(first.result()).isEqualTo("PAYMENT_SUCCEEDED");
        assertThat(accept(body, nonce(), now()).result()).isEqualTo("IDEMPOTENT");
        assertThat(value("SELECT status FROM sales_order WHERE order_id=?", orderId)).isEqualTo("PAID");
        assertThat(value("SELECT status FROM payment_order WHERE payment_no=?", paymentNo)).isEqualTo("SUCCEEDED");
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='PAYMENT_SUCCEEDED'", paymentNo)).isOne();

        assertRejected(body, nonce, now(), "NONCE_REPLAY");
        byte[] changed = body.clone(); changed[changed.length - 2] ^= 1;
        assertRejectedSignature(changed, nonce(), now(), provider.sign(now(), nonce(), body));
        assertRejectedSignature(body, nonce(), now(), "0".repeat(64));
        assertRejectedSignature(body, nonce(), Long.toString(Instant.now().minus(Duration.ofHours(1)).getEpochSecond()), "0".repeat(64));
        assertRejectedSignature(body, nonce(), Long.toString(Instant.now().plus(Duration.ofHours(1)).getEpochSecond()), "0".repeat(64));
    }

    @Test
    void callbackIdConflictAmountTamperingAndNonceReuseDoNotChangeBusinessState() throws Exception {
        String orderId = order(104, "41.00", "PENDING");
        String paymentNo = tx.execute(ignored -> payments.create(104, orderId)).paymentNo();
        String callbackId = UUID.randomUUID().toString();
        byte[] failed = body(callbackId, paymentNo, "FAILED", "41.00");
        accept(failed, nonce(), now());
        assertThat(value("SELECT status FROM sales_order WHERE order_id=?", orderId)).isEqualTo("PENDING");
        assertThat(value("SELECT status FROM payment_order WHERE payment_no=?", paymentNo)).isEqualTo("FAILED");
        byte[] conflict = body(callbackId, paymentNo, "SUCCEEDED", "41.00");
        assertRejected(conflict, nonce(), now(), "CALLBACK_ID_CONFLICT");
        assertRejected(body(UUID.randomUUID().toString(), paymentNo, "SUCCEEDED", "99.00"), nonce(), now(), "PAYMENT_FACT_CONFLICT");

        String reused = nonce();
        byte[] one = body(UUID.randomUUID().toString(), paymentNo, "FAILED", "41.00");
        accept(one, reused, now());
        byte[] two = body(UUID.randomUUID().toString(), paymentNo, "FAILED", "41.00");
        assertRejected(two, reused, now(), "NONCE_REPLAY");
    }

    @Test
    void timeoutWinnerProducesExplicitLateSuccess() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO catalog_product(sku,name,price,stock,status) VALUES(?,?,50.00,4,'ACTIVE')",
                "LATE-" + suffix, "late-success");
        long productId = jdbc.queryForObject("SELECT product_id FROM catalog_product WHERE sku=?",
                Long.class, "LATE-" + suffix);
        String orderId = "ord_" + suffix;
        jdbc.update("""
            INSERT INTO sales_order(order_id,user_id,total_amount,currency,status,expires_at)
            VALUES(?,105,50.00,'CNY','PENDING',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND))
            """, orderId);
        jdbc.update("INSERT INTO sales_order_item(order_id,product_id,quantity,price,line_amount) VALUES(?,?,1,50.00,50.00)",
                orderId, productId);
        String paymentNo = payment(orderId, "PENDING", "50.00");
        long expiresAtMs = jdbc.queryForObject("""
            SELECT TIMESTAMPDIFF(MICROSECOND,'1970-01-01 00:00:00',expires_at) DIV 1000
              FROM sales_order WHERE order_id=?
            """, Long.class, orderId);
        var timeout = new OrderTimeoutService.TimeoutEvent(UUID.randomUUID().toString(),
                "LEGACY_ORDER_TIMEOUT_REQUESTED", "ORDER", orderId, orderId, 105,
                new BigDecimal("50.00"), "CNY", expiresAtMs, 0, Instant.now());
        OrderTimeoutService.ProcessResult timeoutResult = tx.execute(
                ignored -> new OrderTimeoutService(jdbc, json).process(timeout));
        assertThat(timeoutResult).isEqualTo(OrderTimeoutService.ProcessResult.CANCELED);
        var response = accept(body(UUID.randomUUID().toString(), paymentNo, "SUCCEEDED", "50.00"), nonce(), now());
        assertThat(response.result()).isEqualTo("PAYMENT_LATE_SUCCEEDED");
        assertThat(value("SELECT status FROM sales_order WHERE order_id=?", orderId)).isEqualTo("CANCELED");
        assertThat(value("SELECT status FROM payment_order WHERE payment_no=?", paymentNo)).isEqualTo("LATE_SUCCEEDED");
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='PAYMENT_LATE_SUCCEEDED'", paymentNo)).isOne();
    }

    @Test
    void auditFailureRollsBackSuccessfulPaymentTransaction() throws Exception {
        String orderId = order(106, "60.00", "PENDING");
        String paymentNo = tx.execute(ignored -> payments.create(106, orderId)).paymentNo();
        auditWriter.fail = true;
        try {
            assertThatThrownBy(() -> accept(body(UUID.randomUUID().toString(), paymentNo, "SUCCEEDED", "60.00"), nonce(), now()))
                    .isInstanceOf(IllegalStateException.class);
        } finally { auditWriter.fail = false; }
        assertThat(value("SELECT status FROM sales_order WHERE order_id=?", orderId)).isEqualTo("PENDING");
        assertThat(value("SELECT status FROM payment_order WHERE payment_no=?", paymentNo)).isEqualTo("PENDING");
        assertThat(count("SELECT COUNT(*) FROM payment_callback_ledger WHERE payment_no=?", paymentNo)).isZero();
        assertThat(count("SELECT COUNT(*) FROM outbox_event WHERE aggregate_id=? AND event_type='PAYMENT_SUCCEEDED'", paymentNo)).isZero();
    }

    @Test
    void invalidCallbackIsAuditedWithoutChangingBusinessState() throws Exception {
        String orderId = order(111, "71.00", "PENDING");
        String paymentNo = tx.execute(ignored -> payments.create(111, orderId)).paymentNo();
        byte[] tamperedFacts = body(UUID.randomUUID().toString(), paymentNo, "SUCCEEDED", "72.00");
        String nonce = nonce();
        CallbackRejectedException rejected = catchThrowableOfType(
                () -> accept(tamperedFacts, nonce, now()), CallbackRejectedException.class);
        callbacks.auditRejected(rejected, request());

        assertThat(value("SELECT status FROM sales_order WHERE order_id=?", orderId)).isEqualTo("PENDING");
        assertThat(value("SELECT status FROM payment_order WHERE payment_no=?", paymentNo)).isEqualTo("PENDING");
        Map<String, Object> audit = jdbc.queryForMap("""
            SELECT action,result,state_summary FROM audit_log
             WHERE resource_id=? AND action='MOCK_PAYMENT_CALLBACK_REJECTED'
             ORDER BY audit_id DESC LIMIT 1
            """, paymentNo);
        assertThat(audit).containsEntry("action", "MOCK_PAYMENT_CALLBACK_REJECTED")
                .containsEntry("result", "DENIED");
        assertThat(audit.get("state_summary").toString())
                .contains("PAYMENT_FACT_CONFLICT")
                .doesNotContain(nonce)
                .doesNotContain("signature")
                .doesNotContain("secret");
    }

    private static com.real.common.api.dto.MockPaymentCallbackResponse accept(byte[] body, String nonce, String timestamp) {
        return callbacks.accept(timestamp, nonce, provider.sign(timestamp, nonce, body), body, request());
    }
    private static void assertRejected(byte[] body, String nonce, String timestamp, String category) {
        assertThatThrownBy(() -> accept(body, nonce, timestamp)).isInstanceOf(CallbackRejectedException.class)
                .hasMessage(category);
    }
    private static void assertRejectedSignature(byte[] body, String nonce, String timestamp, String signature) {
        assertThatThrownBy(() -> callbacks.accept(timestamp, nonce, signature, body, request()))
                .isInstanceOf(CallbackRejectedException.class);
    }
    private static String order(long user, String amount, String status) {
        String id = "ord_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO sales_order(order_id,user_id,total_amount,currency,status,expires_at) VALUES(?,?,?,'CNY',?,DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 15 MINUTE))",
                id, user, new BigDecimal(amount), status);
        return id;
    }
    private static String payment(String orderId, String status, String amount) {
        String no = "MOCK_" + UUID.randomUUID().toString().replace("-", "");
        jdbc.update("INSERT INTO payment_order(payment_no,order_id,provider,amount,currency,status,expires_at) SELECT ?,order_id,'MOCK',?,'CNY',?,expires_at FROM sales_order WHERE order_id=?",
                no, new BigDecimal(amount), status, orderId);
        return no;
    }
    private static String paymentNo(String orderId) { return value("SELECT payment_no FROM payment_order WHERE order_id=?", orderId); }
    private static byte[] body(String callbackId, String paymentNo, String outcome, String amount) throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("callbackId", callbackId); map.put("paymentNo", paymentNo);
        map.put("providerTransactionNo", "MOCK-TXN-" + UUID.randomUUID().toString().replace("-", ""));
        map.put("outcome", outcome); map.put("amount", amount); map.put("currency", "CNY");
        map.put("occurredAt", Instant.now().toString());
        return json.writeValueAsBytes(map);
    }
    private static String nonce() { return UUID.randomUUID().toString().replace("-", ""); }
    private static String now() { return Long.toString(Instant.now().getEpochSecond()); }
    private static int count(String sql, Object... args) { return jdbc.queryForObject(sql, Integer.class, args); }
    private static String value(String sql, Object... args) { return jdbc.queryForObject(sql, String.class, args); }
    private static PaymentResponse get(java.util.concurrent.Future<PaymentResponse> future) {
        try { return future.get(15, java.util.concurrent.TimeUnit.SECONDS); }
        catch (Exception exception) { throw new RuntimeException(exception); }
    }
    private static HttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(com.real.common.api.RequestContext.REQUEST_ID_ATTRIBUTE, UUID.randomUUID().toString());
        request.setAttribute(com.real.common.api.RequestContext.TRACE_ID_ATTRIBUTE, "a".repeat(32));
        return request;
    }

    static final class RecordingAuditWriter implements AuditLogWriter {
        final JdbcTemplate jdbc; final TransactionTemplate requiresNew; final ObjectMapper json; volatile boolean fail;
        RecordingAuditWriter(JdbcTemplate jdbc, TransactionTemplate template, ObjectMapper json) {
            this.jdbc = jdbc; this.json = json;
            this.requiresNew = new TransactionTemplate(template.getTransactionManager());
            this.requiresNew.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        }
        public void append(AuditEvent event) { if (fail) throw new IllegalStateException("audit unavailable"); insert(event); }
        public void appendFailure(AuditEvent event) { requiresNew.executeWithoutResult(ignored -> insert(event)); }
        private void insert(AuditEvent event) {
            try {
                jdbc.update("""
                    INSERT INTO audit_log(actor_type,actor_id,action,resource_type,resource_id,result,
                      request_id,trace_id,source,state_summary) VALUES('SYSTEM',NULL,?,?,?,?,?,?,?,CAST(? AS JSON))
                    """, event.action().name(), event.resource().type().name(), event.resource().id(),
                        event.result().name(), event.requestId(), event.traceId(), event.source().name(),
                        json.writeValueAsString(event.stateSummary()));
            } catch (Exception exception) { throw new IllegalStateException(exception); }
        }
    }
}
