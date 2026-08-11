package com.real.portal.agenttools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.domain.entity.Order;
import com.real.domain.service.advance.OrderStateService;
import com.real.security.entity.CustomUserDetails;
import com.real.security.util.JwtTokenUtil;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.time.Instant;
import java.util.Base64;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rabbitmq.enabled=false",
        "spring.task.scheduling.enabled=false",
        "hotshop.security.refresh.secure-cookie=false",
        "hotshop.security.rate-limit.agent-exchange.limit=100",
        "hotshop.redis.cache.timeout=2s",
        "hotshop.redis.seckill.timeout=2s",
        "hotshop.agent.purchase-draft-ttl=10m",
        "hotshop.agent.purchase-confirmation-ttl=2m"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AgentToolsAndPurchaseConfirmationIntegrationTest {
    private static final long PRODUCT_ONE = 161_001L;
    private static final long PRODUCT_TWO = 161_002L;
    private static final long PRODUCT_THREE = 161_003L;
    private static final Set<String> ALL_AGENT_SCOPES = Set.of(
            "catalog:read",
            "orders:self:read",
            "reservations:self:read",
            "purchase-drafts:create"
    );

    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_agent_tools")
            .withUsername("hotshop")
            .withPassword("hotshop-test")
            .withUrlParam("connectTimeout", "5000")
            .withCommand("--log-bin-trust-function-creators=1");

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.1-alpine"))
                    .withExposedPorts(6379);

    private static final TestKeys KEYS = TestKeys.create();

    static {
        MYSQL.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.datasource.hikari.initialization-fail-timeout", () -> 60_000);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        KEYS.register(registry);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private JwtTokenUtil jwt;

    @Autowired
    @Qualifier("cacheRedisConnectionFactory")
    private LettuceConnectionFactory cacheRedisConnectionFactory;

    @Autowired
    @Qualifier("seckillRedisConnectionFactory")
    private LettuceConnectionFactory seckillRedisConnectionFactory;

    @MockitoSpyBean
    private OrderStateService orderStateService;

    private long userOne;
    private long userTwo;

    @BeforeAll
    void migrateAndSeedRealDatabase() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(9);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        jdbc.update("""
                INSERT INTO app_user (username, password_hash, email, role, status)
                VALUES ('task16-user-one', 'not-used', 'task16-one@hotshop.invalid', 'ROLE_USER', 'ACTIVE'),
                       ('task16-user-two', 'not-used', 'task16-two@hotshop.invalid', 'ROLE_USER', 'ACTIVE')
                """);
        userOne = userId("task16-user-one");
        userTwo = userId("task16-user-two");
        jdbc.update("""
                INSERT INTO catalog_product (
                    product_id, sku, name, price, stock, category, description, status
                ) VALUES
                    (?, 'TASK16-ONE', 'Agent Alpha', 12.50, 80, 'agent-test', 'safe alpha', 'ACTIVE'),
                    (?, 'TASK16-TWO', 'Agent Beta', 20.00, 70, 'agent-test', 'safe beta', 'ACTIVE'),
                    (?, 'TASK16-THREE', 'Agent Gamma', 7.25, 60, 'agent-test', 'safe gamma', 'ACTIVE')
                """, PRODUCT_ONE, PRODUCT_TWO, PRODUCT_THREE);
        jdbc.update("""
                INSERT INTO sales_order (order_id, user_id, total_amount, status)
                VALUES ('task16-own-order', ?, 12.50, 'PENDING'),
                       ('task16-other-order', ?, 20.00, 'PENDING')
                """, userOne, userTwo);
        jdbc.update("""
                INSERT INTO sale_reservation (
                    reservation_no, activity_id, user_id, product_id, quantity,
                    reserved_amount, currency, status, expires_at
                ) VALUES
                    ('task16-own-reservation', 16101, ?, ?, 1, 12.50, 'CNY', 'RESERVED',
                     DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE)),
                    ('task16-other-reservation', 16102, ?, ?, 1, 20.00, 'CNY', 'RESERVED',
                     DATE_ADD(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE))
                """, userOne, PRODUCT_ONE, userTwo, PRODUCT_TWO);
    }

    @AfterAll
    void removeTemporaryKeysAndConnections() throws Exception {
        cacheRedisConnectionFactory.destroy();
        seckillRedisConnectionFactory.destroy();
        KEYS.delete();
    }

    @Test
    void fixedAllowlistToolsReturnLiveMinimalFactsAndOnlyDelegatedUsersResources() throws Exception {
        String delegation = agentToken(userOne, ALL_AGENT_SCOPES);

        mockMvc.perform(get("/agent/api/v1/tools/products")
                        .queryParam("keyword", "Agent")
                        .queryParam("limit", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(delegation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[0].productId").value(Long.toString(PRODUCT_ONE)))
                .andExpect(content().string(not(containsString("stock"))))
                .andExpect(content().string(not(containsString("deletedAt"))));

        mockMvc.perform(get("/agent/api/v1/tools/products/{id}", PRODUCT_ONE)
                        .header(HttpHeaders.AUTHORIZATION, bearer(delegation)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.productId").value(Long.toString(PRODUCT_ONE)))
                .andExpect(jsonPath("$.price").value("12.50"));

        mockMvc.perform(post("/agent/api/v1/tools/product-comparisons")
                        .header(HttpHeaders.AUTHORIZATION, bearer(delegation))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(comparisonJson(PRODUCT_TWO, PRODUCT_ONE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.products.length()").value(2));

        mockMvc.perform(get("/agent/api/v1/tools/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(delegation)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("task16-own-order")))
                .andExpect(content().string(not(containsString("task16-other-order"))));

        mockMvc.perform(get("/agent/api/v1/tools/orders")
                        .queryParam("userId", Long.toString(userTwo))
                        .header(HttpHeaders.AUTHORIZATION, bearer(delegation)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_TOOL_SCHEMA_INVALID"));

        mockMvc.perform(get("/agent/api/v1/tools/reservations")
                        .header(HttpHeaders.AUTHORIZATION, bearer(delegation)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("task16-own-reservation")))
                .andExpect(content().string(not(containsString("task16-other-reservation"))));

        String otherDelegation = agentToken(userTwo, Set.of("orders:self:read"));
        mockMvc.perform(get("/agent/api/v1/tools/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(otherDelegation)))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("task16-other-order")))
                .andExpect(content().string(not(containsString("task16-own-order"))));
    }

    @Test
    void everyCallRejectsMissingForgedAndWrongBoundaryDelegationClaims() throws Exception {
        String missingScope = agentToken(userOne, Set.of("catalog:read"));
        mockMvc.perform(get("/agent/api/v1/tools/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(missingScope)))
                .andExpect(status().isForbidden());

        String forgedScope = signedAgentToken(
                userOne,
                "hotshop-agent-api",
                "hotshop-agent-service",
                "catalog:read users:write",
                "agent_delegation",
                "agent-delegation+jwt"
        );
        String wrongAudience = signedAgentToken(
                userOne,
                "hotshop-admin-api",
                "hotshop-agent-service",
                "catalog:read",
                "agent_delegation",
                "agent-delegation+jwt"
        );
        String wrongIssuer = signedAgentTokenWithIssuer(
                userOne,
                "https://wrong.hotshop.local/agent-delegation"
        );
        String wrongAzp = signedAgentToken(
                userOne,
                "hotshop-agent-api",
                "untrusted-agent",
                "catalog:read",
                "agent_delegation",
                "agent-delegation+jwt"
        );
        String wrongTokenUse = signedAgentToken(
                userOne,
                "hotshop-agent-api",
                "hotshop-agent-service",
                "catalog:read",
                "user_access",
                "agent-delegation+jwt"
        );
        for (String rejected : List.of(
                forgedScope,
                wrongAudience,
                wrongIssuer,
                wrongAzp,
                wrongTokenUse
        )) {
            mockMvc.perform(get("/agent/api/v1/tools/products")
                            .header(HttpHeaders.AUTHORIZATION, bearer(rejected)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(not(containsString(rejected))));
        }

        String userAccess = userToken(userOne);
        mockMvc.perform(get("/agent/api/v1/tools/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userAccess)))
                .andExpect(status().isUnauthorized());

        String validDelegation = agentToken(userOne, Set.of("purchase-drafts:create"));
        mockMvc.perform(post("/api/v1/orders/purchase-confirmations/consume")
                        .header(HttpHeaders.AUTHORIZATION, bearer(validDelegation))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());

        String administratorAccess = jwt.issueAdministratorAccess(principal(
                userTwo,
                username(userTwo),
                "ROLE_ADMIN"
        )).value();
        mockMvc.perform(post("/api/v1/orders/purchase-confirmations/consume")
                        .header(HttpHeaders.AUTHORIZATION, bearer(administratorAccess))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/orders/purchase-confirmations/consume")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void strictSchemasRejectExtraFieldsTypeConfusionOversizedCollectionsAndIllegalIds()
            throws Exception {
        String catalog = agentToken(userOne, Set.of("catalog:read"));
        mockMvc.perform(post("/agent/api/v1/tools/product-comparisons")
                        .header(HttpHeaders.AUTHORIZATION, bearer(catalog))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "productIds":["161001","161002"],
                                  "url":"https://untrusted.invalid"
                                }
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(get("/agent/api/v1/tools/products/{id}", "0")
                        .header(HttpHeaders.AUTHORIZATION, bearer(catalog)))
                .andExpect(status().isBadRequest());

        String purchase = agentToken(userOne, Set.of("purchase-drafts:create"));
        mockMvc.perform(post("/agent/api/v1/tools/purchase-drafts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(purchase))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"items":[{"productId":"161001","quantity":"2"}]}
                                """))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/agent/api/v1/tools/purchase-drafts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(purchase))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(oversizedDraftJson()))
                .andExpect(status().isBadRequest());
    }

    @Test
    void purchaseDraftIsAnExplicitSnapshotWithoutInventoryOrderOrReservationSideEffects()
            throws Exception {
        int stockBefore = stock(PRODUCT_ONE);
        int ordersBefore = count("SELECT COUNT(*) FROM sales_order");
        int reservationsBefore = count("SELECT COUNT(*) FROM sale_reservation");

        Draft draft = createDraft(userOne, PRODUCT_ONE, 3);

        assertThat(draft.actionType()).isEqualTo("CREATE_ORDER");
        assertThat(draft.confirmationRequired()).isTrue();
        assertThat(draft.nextStep()).containsIgnoringCase("confirm");
        assertThat(stock(PRODUCT_ONE)).isEqualTo(stockBefore);
        assertThat(count("SELECT COUNT(*) FROM sales_order")).isEqualTo(ordersBefore);
        assertThat(count("SELECT COUNT(*) FROM sale_reservation")).isEqualTo(reservationsBefore);
        assertThat(count("SELECT COUNT(*) FROM purchase_draft WHERE draft_id = ?", draft.draftId()))
                .isEqualTo(1);
        assertThat(count("SELECT COUNT(*) FROM purchase_draft_item WHERE draft_id = ?", draft.draftId()))
                .isEqualTo(1);
    }

    @Test
    void loggedInUserConfirmsAndConsumesOnceWithAuditedOpaqueToken() throws Exception {
        Draft draft = createDraft(userOne, PRODUCT_ONE, 2);
        int stockBefore = stock(PRODUCT_ONE);
        Confirmation confirmation = issue(userOne, draft.draftId(), "CREATE_ORDER");

        assertThat(confirmation.token()).hasSize(43);
        assertThat(count(
                "SELECT COUNT(*) FROM purchase_confirmation WHERE token_hash = ?",
                sha256(confirmation.token())
        )).isEqualTo(1);
        assertThat(columnNames("purchase_confirmation")).doesNotContain("token");

        MvcResult consumed = consume(userOne, confirmation, PRODUCT_ONE, 2, "CREATE_ORDER")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String orderId = objectMapper.readTree(consumed.getResponse().getContentAsString())
                .get("orderId").asText();
        assertThat(count("SELECT COUNT(*) FROM sales_order WHERE order_id = ?", orderId)).isEqualTo(1);
        assertThat(stock(PRODUCT_ONE)).isEqualTo(stockBefore - 2);

        consume(userOne, confirmation, PRODUCT_ONE, 2, "CREATE_ORDER")
                .andExpect(status().isConflict());
        assertThat(count("SELECT COUNT(*) FROM sales_order WHERE order_id = ?", orderId)).isEqualTo(1);

        String audit = jdbc.queryForObject(
                "SELECT COALESCE(GROUP_CONCAT(CAST(state_summary AS CHAR)), '') FROM audit_log",
                String.class
        );
        assertThat(audit)
                .doesNotContain(confirmation.token())
                .contains("CREATE_ORDER");
        assertThat(count("""
                SELECT COUNT(*) FROM audit_log
                 WHERE action IN (
                    'AGENT_TOOL_INVOKED',
                    'PURCHASE_CONFIRMATION_ISSUED',
                    'PURCHASE_CONFIRMATION_CONSUMED',
                    'PURCHASE_CONFIRMATION_CONSUME_DENIED'
                 )
                """)).isGreaterThanOrEqualTo(4);
    }

    @Test
    void concurrentConsumptionHasExactlyOneSuccessAndOneDurableOrder() throws Exception {
        Draft draft = createDraft(userOne, PRODUCT_TWO, 1);
        Confirmation confirmation = issue(userOne, draft.draftId(), "CREATE_ORDER");
        int ordersBefore = count("SELECT COUNT(*) FROM sales_order WHERE user_id = ?", userOne);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var attempt = (java.util.concurrent.Callable<Integer>) () -> {
                ready.countDown();
                assertThat(start.await(10, TimeUnit.SECONDS)).isTrue();
                return consume(userOne, confirmation, PRODUCT_TWO, 1, "CREATE_ORDER")
                        .andReturn().getResponse().getStatus();
            };
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(200, 409);
        } finally {
            executor.shutdownNow();
        }

        assertThat(count("SELECT COUNT(*) FROM sales_order WHERE user_id = ?", userOne))
                .isEqualTo(ordersBefore + 1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM purchase_confirmation WHERE draft_id = ?",
                String.class,
                draft.draftId()
        )).isEqualTo("CONSUMED");
        assertThat(count("""
                SELECT COUNT(*) FROM sales_order
                 WHERE order_id = (
                    SELECT order_id FROM purchase_confirmation WHERE draft_id = ?
                 )
                """, draft.draftId())).isEqualTo(1);
    }

    @Test
    void crossUserTamperingWrongActionExpiryAndRevocationAreRejected() throws Exception {
        Draft draft = createDraft(userOne, PRODUCT_THREE, 2);
        Confirmation confirmation = issue(userOne, draft.draftId(), "CREATE_ORDER");

        consume(userTwo, confirmation, PRODUCT_THREE, 2, "CREATE_ORDER")
                .andExpect(status().isConflict());
        consume(userOne, confirmation, PRODUCT_THREE, 1, "CREATE_ORDER")
                .andExpect(status().isConflict());
        consume(userOne, confirmation, PRODUCT_THREE, 2, "REFUND")
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/v1/orders/purchase-drafts/{draftId}/confirmation", draft.draftId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken(userOne))))
                .andExpect(status().isNoContent());
        consume(userOne, confirmation, PRODUCT_THREE, 2, "CREATE_ORDER")
                .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject(
                "SELECT status FROM purchase_confirmation WHERE draft_id = ?",
                String.class,
                draft.draftId()
        )).isEqualTo("REVOKED");

        Draft expiring = createDraft(userOne, PRODUCT_THREE, 1);
        Confirmation expired = issue(userOne, expiring.draftId(), "CREATE_ORDER");
        jdbc.update("""
                UPDATE purchase_confirmation
                   SET issued_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 10 MINUTE),
                       expires_at = DATE_SUB(UTC_TIMESTAMP(6), INTERVAL 1 MINUTE),
                       updated_at = UTC_TIMESTAMP(6)
                 WHERE draft_id = ?
                """, expiring.draftId());
        consume(userOne, expired, PRODUCT_THREE, 1, "CREATE_ORDER")
                .andExpect(status().isConflict());

        Draft wrongAction = createDraft(userOne, PRODUCT_THREE, 1);
        mockMvc.perform(post("/api/v1/orders/purchase-drafts/{draftId}/confirmations", wrongAction.draftId())
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken(userOne)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actionType\":\"REFUND\"}"))
                .andExpect(status().isConflict());
        assertThat(count(
                "SELECT COUNT(*) FROM purchase_confirmation WHERE draft_id = ?",
                wrongAction.draftId()
        )).isZero();
    }

    @Test
    void failedOrderTransactionRollsBackConfirmationConsumptionAndOrderFacts() throws Exception {
        Draft draft = createDraft(userOne, PRODUCT_TWO, 2);
        Confirmation confirmation = issue(userOne, draft.draftId(), "CREATE_ORDER");
        int stockBefore = stock(PRODUCT_TWO);
        int ordersBefore = count("SELECT COUNT(*) FROM sales_order WHERE user_id = ?", userOne);
        doThrow(new IllegalStateException("forced order persistence failure"))
                .when(orderStateService)
                .createOrder(
                        any(Order.class),
                        nullable(String.class),
                        nullable(String.class),
                        nullable(String.class)
                );
        try {
            consume(userOne, confirmation, PRODUCT_TWO, 2, "CREATE_ORDER")
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().string(not(containsString(confirmation.token()))));
        } finally {
            reset(orderStateService);
        }

        Map<String, Object> state = jdbc.queryForMap("""
                SELECT status, consumed_at, order_id
                  FROM purchase_confirmation
                 WHERE draft_id = ?
                """, draft.draftId());
        assertThat(state.get("status")).isEqualTo("ISSUED");
        assertThat(state.get("consumed_at")).isNull();
        assertThat(state.get("order_id")).isNull();
        assertThat(stock(PRODUCT_TWO)).isEqualTo(stockBefore);
        assertThat(count("SELECT COUNT(*) FROM sales_order WHERE user_id = ?", userOne))
                .isEqualTo(ordersBefore);
    }

    private Draft createDraft(long userId, long productId, int quantity) throws Exception {
        MvcResult result = mockMvc.perform(post("/agent/api/v1/tools/purchase-drafts")
                        .header(HttpHeaders.AUTHORIZATION, bearer(agentToken(
                                userId,
                                Set.of("purchase-drafts:create")
                        )))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(draftJson(productId, quantity)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].productId").value(Long.toString(productId)))
                .andExpect(jsonPath("$.items[0].quantity").value(quantity))
                .andExpect(jsonPath("$.confirmationRequired").value(true))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Draft(
                body.get("draftId").asText(),
                body.get("actionType").asText(),
                body.get("confirmationRequired").asBoolean(),
                body.get("nextStep").asText()
        );
    }

    private Confirmation issue(long userId, String draftId, String action) throws Exception {
        MvcResult result = mockMvc.perform(post(
                        "/api/v1/orders/purchase-drafts/{draftId}/confirmations",
                        draftId
                )
                        .header(HttpHeaders.AUTHORIZATION, bearer(userToken(userId)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("actionType", action))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.draftId").value(draftId))
                .andExpect(jsonPath("$.actionType").value(action))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return new Confirmation(draftId, body.get("confirmationToken").asText());
    }

    private org.springframework.test.web.servlet.ResultActions consume(
            long userId,
            Confirmation confirmation,
            long productId,
            int quantity,
            String action
    ) throws Exception {
        return mockMvc.perform(post("/api/v1/orders/purchase-confirmations/consume")
                .header(HttpHeaders.AUTHORIZATION, bearer(userToken(userId)))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "confirmationToken", confirmation.token(),
                        "draftId", confirmation.draftId(),
                        "actionType", action,
                        "items", List.of(Map.of(
                                "productId", Long.toString(productId),
                                "quantity", quantity
                        ))
                ))));
    }

    private String draftJson(long productId, int quantity) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "items",
                List.of(Map.of("productId", Long.toString(productId), "quantity", quantity))
        ));
    }

    private String comparisonJson(long first, long second) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "productIds",
                List.of(Long.toString(first), Long.toString(second))
        ));
    }

    private String userToken(long userId) {
        return jwt.issueUserAccess(principal(userId, username(userId), "ROLE_USER")).value();
    }

    private String agentToken(long userId, Set<String> scopes) {
        return jwt.issueAgentDelegation(
                userId,
                username(userId),
                "hotshop-agent-service",
                scopes
        ).value();
    }

    private String signedAgentToken(
            long userId,
            String audience,
            String azp,
            String scope,
            String tokenUse,
            String type
    ) {
        Instant now = Instant.now();
        Map<String, Object> claims = new LinkedHashMap<>();
        claims.put("token_use", tokenUse);
        claims.put("preferred_username", username(userId));
        claims.put("azp", azp);
        claims.put("scope", scope);
        return sign(
                KEYS.agentDelegation.privateKey(),
                "agent-delegation-local-1",
                type,
                "https://auth.hotshop.local/agent-delegation",
                audience,
                Long.toString(userId),
                now,
                now.plusSeconds(240),
                claims
        );
    }

    private String signedAgentTokenWithIssuer(long userId, String issuer) {
        Instant now = Instant.now();
        return sign(
                KEYS.agentDelegation.privateKey(),
                "agent-delegation-local-1",
                "agent-delegation+jwt",
                issuer,
                "hotshop-agent-api",
                Long.toString(userId),
                now,
                now.plusSeconds(240),
                Map.of(
                        "token_use", "agent_delegation",
                        "preferred_username", username(userId),
                        "azp", "hotshop-agent-service",
                        "scope", "catalog:read"
                )
        );
    }

    private String sign(
            PrivateKey key,
            String kid,
            String type,
            String issuer,
            String audience,
            String subject,
            Instant issuedAt,
            Instant expiresAt,
            Map<String, Object> claims
    ) {
        return Jwts.builder()
                .setHeaderParam("kid", kid)
                .setHeaderParam("typ", type)
                .setClaims(claims)
                .setIssuer(issuer)
                .setAudience(audience)
                .setSubject(subject)
                .setIssuedAt(Date.from(issuedAt))
                .setNotBefore(Date.from(issuedAt))
                .setExpiration(Date.from(expiresAt))
                .setId(UUID.randomUUID().toString())
                .signWith(key, SignatureAlgorithm.RS256)
                .compact();
    }

    private CustomUserDetails principal(long id, String username, String authority) {
        return CustomUserDetails.builder()
                .userId(id)
                .username(username)
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority(authority)))
                .build();
    }

    private long userId(String username) {
        return jdbc.queryForObject(
                "SELECT user_id FROM app_user WHERE username = ?",
                Long.class,
                username
        );
    }

    private String username(long userId) {
        return userId == userOne ? "task16-user-one" : "task16-user-two";
    }

    private int stock(long productId) {
        return jdbc.queryForObject(
                "SELECT stock FROM catalog_product WHERE product_id = ?",
                Integer.class,
                productId
        );
    }

    private int count(String sql, Object... parameters) {
        return jdbc.queryForObject(sql, Integer.class, parameters);
    }

    private String oversizedDraftJson() throws Exception {
        var items = new java.util.ArrayList<Map<String, Object>>();
        for (int index = 1; index <= 21; index++) {
            items.add(Map.of("productId", Long.toString(200_000L + index), "quantity", 1));
        }
        return objectMapper.writeValueAsString(Map.of("items", items));
    }

    private List<String> columnNames(String table) {
        return jdbc.queryForList("""
                SELECT column_name
                  FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = ?
                 ORDER BY ordinal_position
                """, String.class, table);
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record Draft(
            String draftId,
            String actionType,
            boolean confirmationRequired,
            String nextStep
    ) { }

    private record Confirmation(String draftId, String token) { }

    private static final class TestKeys {
        private final Path directory;
        private final KeyMaterial user;
        private final KeyMaterial administrator;
        private final KeyMaterial agentDelegation;
        private final KeyMaterial agentService;

        private TestKeys(
                Path directory,
                KeyMaterial user,
                KeyMaterial administrator,
                KeyMaterial agentDelegation,
                KeyMaterial agentService
        ) {
            this.directory = directory;
            this.user = user;
            this.administrator = administrator;
            this.agentDelegation = agentDelegation;
            this.agentService = agentService;
        }

        static TestKeys create() {
            try {
                Path directory = Files.createTempDirectory("hotshop-task16-keys-");
                return new TestKeys(
                        directory,
                        createKey(directory, "user"),
                        createKey(directory, "administrator"),
                        createKey(directory, "agent-delegation"),
                        createKey(directory, "agent-service")
                );
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        void register(DynamicPropertyRegistry registry) {
            addDomain(registry, "user", "user-local-1", user);
            addDomain(registry, "administrator", "administrator-local-1", administrator);
            addDomain(registry, "agent-delegation", "agent-delegation-local-1", agentDelegation);
            registry.add(
                    "hotshop.security.client-assertion.verification-key-paths.agent-service-local-1",
                    () -> agentService.publicPath().toString()
            );
        }

        private void addDomain(
                DynamicPropertyRegistry registry,
                String domain,
                String kid,
                KeyMaterial material
        ) {
            registry.add(
                    "hotshop.security." + domain + ".private-key-path",
                    () -> material.privatePath().toString()
            );
            registry.add(
                    "hotshop.security." + domain + ".verification-key-paths." + kid,
                    () -> material.publicPath().toString()
            );
        }

        void delete() throws Exception {
            for (Path file : List.of(
                    user.privatePath(), user.publicPath(),
                    administrator.privatePath(), administrator.publicPath(),
                    agentDelegation.privatePath(), agentDelegation.publicPath(),
                    agentService.privatePath(), agentService.publicPath()
            )) {
                Files.deleteIfExists(file);
            }
            Files.deleteIfExists(directory);
        }

        private static KeyMaterial createKey(Path directory, String name) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Path privatePath = directory.resolve(name + "-private.pem");
            Path publicPath = directory.resolve(name + "-public.pem");
            Files.writeString(privatePath, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(publicPath, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            return new KeyMaterial(pair, privatePath, publicPath);
        }

        private static String pem(String label, byte[] encoded) {
            String base64 = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(encoded);
            return "-----BEGIN " + label + "-----\n"
                    + base64
                    + "\n-----END " + label + "-----\n";
        }
    }

    private record KeyMaterial(KeyPair pair, Path privatePath, Path publicPath) {
        PrivateKey privateKey() {
            return pair.getPrivate();
        }
    }
}
