package com.real.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.real.admin.controller.AdminProductController;
import com.real.admin.service.AdminProductAuditService;
import com.real.common.handler.GlobalExceptionHandler;
import com.real.common.util.PageHelperUtils;
import com.real.domain.adminops.AdminProductMutationRepository;
import com.real.domain.entity.Order;
import com.real.domain.entity.OrderItem;
import com.real.domain.entity.Product;
import com.real.domain.mapper.FlashSaleActivityMapper;
import com.real.domain.mapper.OrderMapper;
import com.real.domain.mapper.ProductMapper;
import com.real.domain.messaging.OutboxMapper;
import com.real.domain.service.ProductService;
import com.real.domain.service.advance.OrderStateService;
import com.real.domain.service.seckill.FlashSaleActivityLoader;
import com.real.domain.service.seckill.FlashSaleLoadCode;
import com.real.domain.service.seckill.FlashSaleReservationCode;
import com.real.domain.service.seckill.FlashSaleReservationService;
import com.real.infrastructure.redis.SeckillRedisKeys;
import com.real.security.audit.AuditSensitiveDataSanitizer;
import com.real.security.audit.JdbcAuditLogWriter;
import com.real.security.entity.CustomUserDetails;
import com.real.task.seckill.NoOpSeckillProcessingFailpoint;
import com.real.task.seckill.ReservationStreamConsumer;
import com.real.task.seckill.SeckillOrderMetrics;
import com.real.task.seckill.SeckillOrderProperties;
import com.real.task.seckill.SeckillProcessingService;
import com.real.task.seckill.SeckillReconciliationService;
import com.real.task.seckill.SeckillRedisReservationGateway;
import com.real.task.timeoutOrderTask.OrderTimeoutService;
import com.real.task.payment.SeckillPaymentExpiredDeliveryProperties;
import com.real.task.payment.SeckillPaymentExpiredProjectionConsumer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real HTTP binding, transaction proxies, MyBatis, Flyway, MySQL and Redis/Lua.
 * Authentication is supplied explicitly; this is not an authorization-filter or browser test.
 */
@Testcontainers
class InventoryReconciliationJointContainerTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_review_joint").withUsername("hotshop").withPassword("joint-test")
            .withCommand("--log-bin-trust-function-creators=1");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:8.8.1-alpine")
            .withExposedPorts(6379);
    static JdbcTemplate jdbc;
    static ObjectMapper json;
    static ProductMapper products;
    static OrderStateService orders;
    static OrderTimeoutService timeouts;
    static FlashSaleActivityLoader loader;
    static FlashSaleReservationService reservations;
    static ReservationStreamConsumer consumer;
    static SeckillReconciliationService reconciliation;
    static StringRedisTemplate redis;
    static LettuceConnectionFactory connection;
    static MockMvc mvc;
    static DataSourceTransactionManager transactions;
    static SimpleMeterRegistry meters;

    @BeforeAll
    static void setup() throws Exception {
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
        transactions = new DataSourceTransactionManager(source);
        json = new ObjectMapper().findAndRegisterModules();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.real.domain.entity");
        factory.setMapperLocations(
                new ClassPathResource("com/real/domain/mapper/ProductMapper.xml"),
                new ClassPathResource("com/real/domain/mapper/OrderMapper.xml"),
                new ClassPathResource("com/real/domain/mapper/FlashSaleActivityMapper.xml"));
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        config.addMapper(OutboxMapper.class);
        factory.setConfiguration(config);
        var session = new SqlSessionTemplate(factory.getObject());
        products = session.getMapper(ProductMapper.class);
        var productService = new ProductService(new PageHelperUtils<Product>(), products);
        var writer = transactional(new JdbcAuditLogWriter(jdbc, json, new AuditSensitiveDataSanitizer(json)),
                JdbcAuditLogWriter.class);
        var admin = transactional(new AdminProductAuditService(productService,
                new AdminProductMutationRepository(jdbc), writer), AdminProductAuditService.class);
        // Query-only operationsService is unused by these mutation endpoints.
        mvc = MockMvcBuilders.standaloneSetup(new AdminProductController(productService, admin, null))
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .setControllerAdvice(new GlobalExceptionHandler()).build();
        orders = transactional(new OrderStateService(session.getMapper(OrderMapper.class), products,
                session.getMapper(OutboxMapper.class), json, Duration.ofMinutes(15)), OrderStateService.class);
        timeouts = transactional(new OrderTimeoutService(jdbc, json), OrderTimeoutService.class);
        connection = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        connection.afterPropertiesSet();
        redis = new StringRedisTemplate(connection);
        loader = new FlashSaleActivityLoader(session.getMapper(FlashSaleActivityMapper.class), redis,
                Duration.ofDays(7));
        reservations = new FlashSaleReservationService(redis, Duration.ofDays(7), Duration.ofDays(1));
        var properties = new SeckillOrderProperties();
        properties.setReadBlock(Duration.ofMillis(1));
        properties.setReconciliationBatch(3);
        properties.setReconciliationDryRun(true);
        properties.setAutoRepair(false);
        var failpoint = new NoOpSeckillProcessingFailpoint();
        var processing = transactional(new SeckillProcessingService(jdbc, json, properties, failpoint),
                SeckillProcessingService.class);
        var gateway = new SeckillRedisReservationGateway(redis);
        meters = new SimpleMeterRegistry();
        var metrics = new SeckillOrderMetrics(meters);
        consumer = new ReservationStreamConsumer(redis, properties, gateway, processing, failpoint, metrics);
        reconciliation = new SeckillReconciliationService(redis, jdbc, properties, gateway, processing, metrics);
    }

    @AfterAll
    static void closeOnlyOwnedResources() {
        SecurityContextHolder.clearContext();
        if (connection != null) connection.destroy();
        if (meters != null) meters.close();
    }

    @Test
    void staleHttpFormCannotUndoRealOrdinaryOrder() throws Exception {
        authenticate();
        long product = createProduct();
        String stale = editBody("renamed stale form", 100);
        ordinaryPurchase(product, 1);
        mvc.perform(put("/admin/api/v1/products/{id}", product)
                        .contentType(MediaType.APPLICATION_JSON).content(stale))
                .andExpect(status().isOk()).andExpect(jsonPath("stock").value(99));
        assertThat(products.selectById(product).getStock()).isEqualTo(99);
    }

    @Test
    void failedAdjustmentAuditRollsBackStockBalanceAndVersionTogether() throws Exception {
        authenticate();
        long product = createProduct();
        String version = jdbc.queryForObject(
                "SELECT CAST(version AS CHAR) FROM catalog_product WHERE product_id=?", String.class, product);
        String request = json.writeValueAsString(Map.of(
                "delta", 5, "expectedVersion", version, "reason", "audit rollback verification"));
        // Fail only the success audit in this disposable database. The production
        // REQUIRES_NEW failure audit must survive while the stock transaction rolls back.
        jdbc.execute("""
                CREATE TRIGGER review_reject_stock_audit BEFORE INSERT ON audit_log FOR EACH ROW
                BEGIN
                  IF NEW.action='CATALOG_STOCK_ADJUSTED' AND NEW.result='SUCCESS' THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='review injected audit storage failure';
                  END IF;
                END
                """);
        try {
            mvc.perform(post("/admin/api/v1/products/{id}/stock-adjustments", product)
                            .contentType(MediaType.APPLICATION_JSON).content(request))
                    .andExpect(status().isInternalServerError());
            assertStock(product, 100);
            assertThat(jdbc.queryForObject(
                    "SELECT CAST(version AS CHAR) FROM catalog_product WHERE product_id=?", String.class, product))
                    .isEqualTo(version);
            assertThat(number("SELECT COUNT(*) FROM audit_log WHERE action='CATALOG_STOCK_ADJUSTED' "
                    + "AND result='FAILURE' AND resource_id=?", Long.toString(product))).isOne();
            assertThat(number("SELECT COUNT(*) FROM audit_log WHERE action='CATALOG_STOCK_ADJUSTED' "
                    + "AND result='SUCCESS' AND resource_id=?", Long.toString(product))).isZero();
        } finally {
            jdbc.execute("DROP TRIGGER review_reject_stock_audit");
        }
        mvc.perform(post("/admin/api/v1/products/{id}/stock-adjustments", product)
                        .contentType(MediaType.APPLICATION_JSON).content(request))
                .andExpect(status().isOk()).andExpect(jsonPath("stock").value(105));
        assertStock(product, 105);
        assertThat(number("SELECT COUNT(*) FROM audit_log WHERE action='CATALOG_STOCK_ADJUSTED' "
                + "AND result='SUCCESS' AND resource_id=?", Long.toString(product))).isOne();
    }

    @Test
    void realAdminOrdersTimeoutsAndTwoActivityLoadsConserveInventoryAndDetectTampering() throws Exception {
        authenticate();
        long product = createProduct();
        long firstActivity = product * 10 + 1;
        long secondActivity = product * 10 + 2;
        loadActivity(firstActivity, product, 20);
        String initialVersion = jdbc.queryForObject("SELECT CAST(version AS CHAR) FROM catalog_product WHERE product_id=?",
                String.class, product);
        mvc.perform(post("/admin/api/v1/products/{id}/stock-adjustments", product)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "delta", 10, "expectedVersion", initialVersion, "reason", "joint restock"))))
                .andExpect(status().isOk()).andExpect(jsonPath("stock").value(110));
        assertStock(product, 110);
        assertCleanReconciliation();

        String stale110 = editBody("stale after adjustment", 110);
        String ordinary = ordinaryPurchase(product, 2);
        assertStock(product, 108);
        editAndAssert(product, stale110, 108);
        loadActivity(secondActivity, product, 10); // A different product snapshot, same stock boundary.
        assertCleanReconciliation();

        var reservation = reservations.reserve(firstActivity, 77, 3, UUID.randomUUID().toString(), "joint-seckill");
        assertThat(reservation.code()).isEqualTo(FlashSaleReservationCode.ACCEPTED);
        consumer.refreshStreams();
        consumer.poll();
        String flashOrder = jdbc.queryForObject("SELECT order_id FROM sale_reservation WHERE reservation_no=?",
                String.class, reservation.reservationNo());
        assertThat(flashOrder).isNotBlank();
        assertStock(product, 105);
        editAndAssert(product, stale110, 105);
        assertCleanReconciliation();

        expire(ordinary);
        assertStock(product, 107);
        editAndAssert(product, editBody("old pre-refund form", 105), 107);
        assertCleanReconciliation();
        expire(flashOrder);
        assertStock(product, 110);
        editAndAssert(product, editBody("old pre-refund form", 105), 110);
        assertCleanReconciliation();
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(firstActivity))).isEqualTo("17");
        projectExpiredOutbox(flashOrder);
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(firstActivity))).isEqualTo("20");
        assertThat(redis.opsForHash().get(SeckillRedisKeys.reservation(firstActivity, reservation.reservationNo()),
                "status")).isEqualTo("PAYMENT_EXPIRED");
        assertCleanReconciliation();

        mvc.perform(post("/admin/api/v1/products/{id}/stock-adjustments", product)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of(
                                "delta", 10, "expectedVersion", initialVersion, "reason", "stale stock count"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("code").value("STOCK_ADJUSTMENT_CONFLICT"));
        assertStock(product, 110);
        assertThat(number("SELECT COUNT(*) FROM audit_log WHERE action='CATALOG_STOCK_ADJUSTED' "
                + "AND result='SUCCESS' AND resource_id=?", Long.toString(product))).isOne();
        var adjustment = json.readTree(jdbc.queryForObject("SELECT CAST(state_summary AS CHAR) FROM audit_log "
                + "WHERE action='CATALOG_STOCK_ADJUSTED' AND result='SUCCESS' AND resource_id=?",
                String.class, Long.toString(product)));
        assertThat(adjustment.get("delta").asInt()).isEqualTo(10);
        assertThat(adjustment.get("stockBefore").asInt()).isEqualTo(100);
        assertThat(adjustment.get("stockAfter").asInt()).isEqualTo(110);
        assertThat(adjustment.get("versionBefore").asLong()).isEqualTo(Long.parseLong(initialVersion));
        assertThat(adjustment.get("versionAfter").asLong()).isEqualTo(Long.parseLong(initialVersion) + 1);
        assertThat(adjustment.get("reason").asText()).isEqualTo("joint restock");
        assertThat(number("SELECT COUNT(*) FROM audit_log WHERE action='INVENTORY_COMPENSATED' "
                + "AND resource_id IN (?,?)", ordinary, reservation.reservationNo())).isEqualTo(2);
        assertThat(number("SELECT COUNT(*) FROM sales_order WHERE order_id IN (?,?) AND status='CANCELED'",
                ordinary, flashOrder)).isEqualTo(2);
        assertThat(number("SELECT available_stock FROM flash_sale_activity WHERE activity_id=?", firstActivity))
                .isEqualTo(20);
        assertThat(number("SELECT expected_available_stock FROM flash_sale_activity WHERE activity_id=?", firstActivity))
                .isEqualTo(20);

        jdbc.update("UPDATE catalog_product SET stock=stock+1 WHERE product_id=?", product);
        for (int run = 0; run < 6; run++) reconciliation.runBatch();
        assertThat(number("SELECT COUNT(*) FROM seckill_reconciliation_issue WHERE severity='CRITICAL' "
                + "AND issue_type='MYSQL_STOCK_CONSERVATION_VIOLATION' AND activity_id IN (?,?)",
                firstActivity, secondActivity)).isPositive();
        var evidence = json.readTree(jdbc.queryForObject("SELECT CAST(evidence_summary AS CHAR) "
                + "FROM seckill_reconciliation_issue WHERE issue_type='MYSQL_STOCK_CONSERVATION_VIOLATION' "
                + "AND activity_id=? ORDER BY issue_id LIMIT 1", String.class, firstActivity));
        assertThat(evidence.get("catalogStock").asInt()).isEqualTo(111);
        assertThat(evidence.get("expectedCatalogStock").asInt()).isEqualTo(110);
        assertThat(number("SELECT stock FROM catalog_product WHERE product_id=?", product)).isEqualTo(111);
        assertThat(number("SELECT expected_stock FROM catalog_product WHERE product_id=?", product)).isEqualTo(110);
    }

    private static void authenticate() {
        var authorities = List.of(new SimpleGrantedAuthority("PERM_ADMIN_PRODUCT_WRITE"));
        var administrator = new CustomUserDetails(1L, "joint-admin", "unused", authorities);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(administrator, null, authorities));
    }

    private static long createProduct() throws Exception {
        String body = json.writeValueAsString(Map.of("name", "joint " + UUID.randomUUID(),
                "price", "10.00", "stock", 100, "category", "joint", "description", "joint test",
                "reason", "joint creation"));
        String result = mvc.perform(post("/admin/api/v1/products").contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(result).get("productId").asLong();
    }

    private static String editBody(String name, int staleStock) throws Exception {
        // Include the old browser payload's stock to prove it cannot mutate inventory.
        return json.writeValueAsString(Map.of("name", name, "price", "10.00", "stock", staleStock,
                "category", "joint", "description", "joint edit", "reason", "metadata correction"));
    }

    private static void editAndAssert(long product, String body, int stock) throws Exception {
        mvc.perform(put("/admin/api/v1/products/{id}", product).contentType(MediaType.APPLICATION_JSON)
                        .content(body)).andExpect(status().isOk()).andExpect(jsonPath("stock").value(stock));
        assertStock(product, stock);
    }

    private static String ordinaryPurchase(long product, int quantity) {
        var order = new Order();
        order.setUserId(77L);
        order.setItems(List.of(new OrderItem(null, null, product, quantity, null)));
        return orders.createOrder(order);
    }

    private static void loadActivity(long activity, long product, int quota) {
        jdbc.update("""
                INSERT INTO flash_sale_activity(activity_id,product_id,activity_code,sale_price,total_stock,
                  available_stock,per_user_limit,status,starts_at,ends_at,version)
                VALUES(?,?,?,5.00,?,?,5,'ACTIVE',DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 HOUR),
                  DATE_ADD(UTC_TIMESTAMP(6),INTERVAL 1 DAY),0)
                """, activity, product, "joint " + activity, quota, quota);
        assertThat(loader.load(activity).code()).isEqualTo(FlashSaleLoadCode.LOADED);
        assertThat(redis.opsForValue().get(SeckillRedisKeys.availableStock(activity))).isEqualTo(Integer.toString(quota));
    }

    private static void expire(String orderId) {
        // Move only the deadline, then construct the matching trusted timeout event.
        jdbc.update("UPDATE sales_order SET expires_at=DATE_SUB(UTC_TIMESTAMP(6),INTERVAL 1 SECOND) WHERE order_id=?", orderId);
        var event = jdbc.queryForObject("""
                SELECT user_id,total_amount,TIMESTAMPDIFF(MICROSECOND,'1970-01-01 00:00:00',expires_at) DIV 1000
                  FROM sales_order WHERE order_id=?
                """, (rs, row) -> new OrderTimeoutService.TimeoutEvent(UUID.randomUUID().toString(),
                "LEGACY_ORDER_TIMEOUT_REQUESTED", "ORDER", orderId, orderId, rs.getLong(1),
                rs.getBigDecimal(2), "CNY", rs.getLong(3), 0, Instant.now()), orderId);
        assertThat(timeouts.process(event)).isEqualTo(OrderTimeoutService.ProcessResult.CANCELED);
        assertThat(timeouts.process(event)).isEqualTo(OrderTimeoutService.ProcessResult.DUPLICATE);
    }

    private static void projectExpiredOutbox(String orderId) throws Exception {
        var row = jdbc.queryForMap("SELECT event_id,CAST(payload AS CHAR) payload FROM outbox_event "
                + "WHERE aggregate_id=? AND event_type='SECKILL_PAYMENT_EXPIRED'", orderId);
        byte[] envelope = json.writeValueAsBytes(Map.of("schemaVersion", 1,
                "eventId", row.get("event_id"), "eventType", "SECKILL_PAYMENT_EXPIRED",
                "aggregateType", "ORDER", "aggregateId", orderId, "occurredAt", Instant.now().toString(),
                "payload", json.readTree(row.get("payload").toString())));
        var delivery = new MessageProperties();
        delivery.setDeliveryTag(41);
        // Only Rabbit transport is substituted; production parsing, projection and Redis Lua execute.
        Channel channel = mock(Channel.class);
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        var projection = new SeckillPaymentExpiredProjectionConsumer(json, redis, rabbit,
                new SeckillPaymentExpiredDeliveryProperties());
        projection.consume(new Message(envelope, delivery), channel);
        projection.consume(new Message(envelope, delivery), channel);
        verify(channel, times(2)).basicAck(41, false);
        verifyNoMoreInteractions(channel);
        verifyNoInteractions(rabbit);
    }

    private static void assertCleanReconciliation() {
        for (int run = 0; run < 6; run++) reconciliation.runBatch();
        assertThat(jdbc.queryForList("SELECT issue_type,severity,evidence_summary FROM seckill_reconciliation_issue"))
                .isEmpty();
    }

    private static void assertStock(long product, int expected) {
        assertThat(number("SELECT stock FROM catalog_product WHERE product_id=?", product)).isEqualTo(expected);
        assertThat(number("SELECT expected_stock FROM catalog_product WHERE product_id=?", product)).isEqualTo(expected);
    }

    private static int number(String sql, Object... arguments) {
        return jdbc.queryForObject(sql, Integer.class, arguments);
    }

    private static <T> T transactional(T target, Class<T> type) {
        var proxy = new ProxyFactory(target);
        proxy.setProxyTargetClass(true);
        proxy.addAdvice(new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
        return type.cast(proxy.getProxy());
    }
}
