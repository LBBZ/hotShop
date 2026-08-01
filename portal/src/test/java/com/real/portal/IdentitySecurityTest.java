package com.real.portal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.real.security.entity.CustomUserDetails;
import com.real.security.identity.IdentityType;
import com.real.security.identity.IssuedAccessToken;
import com.real.security.service.RefreshCookieService;
import com.real.security.util.JwtTokenUtil;
import com.real.domain.infra.RabbitMQService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.slf4j.LoggerFactory;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rabbitmq.enabled=false",
        "spring.task.scheduling.enabled=false",
        "hotshop.security.refresh.secure-cookie=false",
        "hotshop.security.rate-limit.user-login-ip.limit=100",
        "hotshop.security.rate-limit.user-login-identity.limit=100",
        "hotshop.security.rate-limit.user-login-failure.limit=2",
        "hotshop.security.rate-limit.user-login-failure.window-seconds=120",
        "hotshop.security.rate-limit.user-refresh.limit=100",
        "hotshop.security.rate-limit.agent-exchange.limit=100",
        "hotshop.redis.cache.timeout=2s",
        "hotshop.redis.seckill.timeout=2s"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class IdentitySecurityTest {
    private static final String USERNAME = "task05-user";
    private static final String ADMIN_USERNAME = "task05-admin";
    private static final String PASSWORD = "Task05-Password!";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotShop")
            .withUsername("hotshop")
            .withPassword("hotshop-test")
            .withUrlParam("connectTimeout", "5000")
            .withCommand("--log-bin-trust-function-creators=1");

    static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:8.8.1-alpine"))
                    .withExposedPorts(6379);

    static final TestKeys KEYS = TestKeys.create();

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
    MockMvc mockMvc;
    @Autowired
    ObjectMapper objectMapper;
    @Autowired
    JdbcTemplate jdbcTemplate;
    @Autowired
    JwtTokenUtil jwtTokenUtil;
    @Autowired
    @Qualifier("cacheRedisConnectionFactory")
    LettuceConnectionFactory cacheRedisConnectionFactory;
    @Autowired
    @Qualifier("seckillRedisConnectionFactory")
    LettuceConnectionFactory seckillRedisConnectionFactory;
    @MockitoBean
    RabbitMQService rabbitMQService;

    long userId;
    long adminId;

    @BeforeAll
    void migrateAndSeed() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();

        String passwordHash = new BCryptPasswordEncoder().encode(PASSWORD);
        jdbcTemplate.update(
                """
                INSERT INTO app_user (username, password_hash, email, role, status)
                VALUES (?, ?, ?, 'ROLE_USER', 'ACTIVE')
                """,
                USERNAME,
                passwordHash,
                "task05-user@hotshop.invalid"
        );
        jdbcTemplate.update(
                """
                INSERT INTO app_user (username, password_hash, email, role, status)
                VALUES (?, ?, ?, 'ROLE_ADMIN', 'ACTIVE')
                """,
                ADMIN_USERNAME,
                passwordHash,
                "task05-admin@hotshop.invalid"
        );
        userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM app_user WHERE username = ?",
                Long.class,
                USERNAME
        );
        adminId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM app_user WHERE username = ?",
                Long.class,
                ADMIN_USERNAME
        );
        jdbcTemplate.update(
                """
                INSERT INTO sales_order (order_id, user_id, total_amount, status)
                VALUES ('task05-own-order', ?, 11.00, 'PENDING'),
                       ('task05-other-order', ?, 22.00, 'PENDING')
                """,
                userId,
                adminId
        );
    }

    @AfterAll
    void keysAreTemporary() throws Exception {
        cacheRedisConnectionFactory.destroy();
        seckillRedisConnectionFactory.destroy();
        KEYS.delete();
    }

    @Test
    @Order(1)
    void userLoginUsesRsaAudienceOpaqueCookiesAndNoStore() throws Exception {
        LoginResult login = loginUser();

        var validated = jwtTokenUtil.validate(login.accessToken(), IdentityType.USER_ACCESS);
        assertThat(validated.subjectUserId()).isEqualTo(userId);
        assertThat(validated.username()).isEqualTo(USERNAME);

        assertThat(login.refreshSetCookie())
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/auth")
                .doesNotContain("Secure");
        assertThat(login.csrfSetCookie())
                .doesNotContain("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/api/v1/auth");
        assertThat(login.response().getResponse().getHeader(HttpHeaders.CACHE_CONTROL))
                .isEqualTo("no-store");
        assertThat(login.response().getResponse().getHeader(HttpHeaders.PRAGMA))
                .isEqualTo("no-cache");

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE token_hash = ?",
                Integer.class,
                login.refreshToken()
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM refresh_token
                WHERE user_id = ? AND CHAR_LENGTH(token_hash) = 64
                  AND CHAR_LENGTH(csrf_hash) = 64 AND session_type = 'USER'
                """,
                Integer.class,
                userId
        )).isPositive();
    }

    @Test
    @Order(2)
    void realFilterRejectsCrossAudienceAndOnlyReturnsOwnedOrders() throws Exception {
        LoginResult login = loginUser();

        mockMvc.perform(get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login.accessToken())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].orderId").value("task05-own-order"))
                .andExpect(content().string(not(containsString("task05-other-order"))));

        IssuedAccessToken adminToken = jwtTokenUtil.issueAdministratorAccess(principal(
                adminId,
                ADMIN_USERNAME,
                "ROLE_ADMIN"
        ));
        mockMvc.perform(get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken.value())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"));

        mockMvc.perform(get("/admin/api/v1/products/1")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login.accessToken())))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/agent/api/v1/anything")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminToken.value())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(3)
    void refreshRotatesWithoutValidAccessThenReuseRevokesFamilyAndAudits() throws Exception {
        LoginResult login = loginUser();
        String expiredAccess = signedUserToken(
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(120),
                Instant.now().minusSeconds(60),
                "expired-access",
                Map.of("authorities", List.of("ROLE_USER"))
        );

        mockMvc.perform(post("/api/v1/auth/refresh")
                        .cookie(
                                new Cookie(RefreshCookieService.USER_REFRESH_COOKIE, login.refreshToken()),
                                new Cookie(RefreshCookieService.USER_CSRF_COOKIE, login.csrfToken())
                        ))
                .andExpect(status().isForbidden());

        MvcResult rotated = refresh(
                login.refreshToken(),
                login.csrfToken(),
                expiredAccess
        ).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        LoginResult successor = fromTokenResponse(rotated, SessionTypeNames.USER);
        assertThat(successor.refreshToken()).isNotEqualTo(login.refreshToken());

        MvcResult reused = refresh(login.refreshToken(), login.csrfToken(), null)
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTHENTICATION_REQUIRED"))
                .andReturn();
        assertThat(reused.getResponse().getHeaders(HttpHeaders.SET_COOKIE))
                .allMatch(value -> value.contains("Max-Age=0"));

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM refresh_token
                WHERE family_id = (
                    SELECT family_id FROM refresh_token WHERE token_hash = ?
                ) AND status = 'ACTIVE'
                """,
                Integer.class,
                sha256(login.refreshToken())
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM audit_log
                WHERE action = 'REFRESH_TOKEN_REUSE_DETECTED'
                  AND JSON_UNQUOTE(JSON_EXTRACT(state_summary, '$.reason')) = 'ROTATED_TOKEN_REUSED'
                """,
                Integer.class
        )).isPositive();
        String summaries = jdbcTemplate.queryForObject(
                """
                SELECT GROUP_CONCAT(CAST(state_summary AS CHAR))
                FROM audit_log WHERE action = 'REFRESH_TOKEN_REUSE_DETECTED'
                """,
                String.class
        );
        assertThat(summaries)
                .doesNotContain(login.refreshToken())
                .doesNotContain(login.csrfToken())
                .doesNotContain(successor.refreshToken());

        refresh(successor.refreshToken(), successor.csrfToken(), null)
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(4)
    void concurrentRefreshCreatesAtMostOneSuccessorAndLoserRevokesFamily() throws Exception {
        LoginResult login = loginUser();
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<Integer> attempt = () -> refresh(
                    login.refreshToken(),
                    login.csrfToken(),
                    null
            ).andReturn().getResponse().getStatus();
            var first = executor.submit(attempt);
            var second = executor.submit(attempt);
            assertThat(List.of(first.get(), second.get()))
                    .containsExactlyInAnyOrder(200, 401);
        } finally {
            executor.shutdownNow();
        }

        Long parentId = jdbcTemplate.queryForObject(
                "SELECT refresh_token_id FROM refresh_token WHERE token_hash = ?",
                Long.class,
                sha256(login.refreshToken())
        );
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM refresh_token WHERE parent_token_id = ?",
                Integer.class,
                parentId
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM refresh_token
                WHERE family_id = (
                    SELECT family_id FROM refresh_token WHERE refresh_token_id = ?
                ) AND status = 'ACTIVE'
                """,
                Integer.class,
                parentId
        )).isZero();
    }

    @Test
    @Order(5)
    void logoutRevokesFamilyAndIsIdempotent() throws Exception {
        LoginResult login = loginUser();
        mockMvc.perform(post("/api/v1/auth/logout")
                        .cookie(
                                new Cookie(RefreshCookieService.USER_REFRESH_COOKIE, login.refreshToken()),
                                new Cookie(RefreshCookieService.USER_CSRF_COOKIE, login.csrfToken())
                        )
                        .header(RefreshCookieService.CSRF_HEADER, login.csrfToken())
                        .header(HttpHeaders.AUTHORIZATION, bearer(login.accessToken())))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"));

        refresh(login.refreshToken(), login.csrfToken(), null)
                .andExpect(status().isUnauthorized());

        MvcResult repeated = mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(header().exists(HttpHeaders.SET_COOKIE))
                .andReturn();
        assertThat(repeated.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).hasSize(2);

        mockMvc.perform(get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(6)
    void tokenExchangeRequiresServiceIdentityRejectsReplayAdminAndRiskyScopes() throws Exception {
        LoginResult userLogin = loginUser();

        mockMvc.perform(post("/agent/api/v1/auth/token-exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectToken":"x","scopes":["catalog:read"]}
                                """))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/agent/api/v1/auth/token-exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exchangeJson(userLogin.accessToken(), "forged.assertion.value",
                                Set.of("catalog:read"))))
                .andExpect(status().isUnauthorized());

        String assertion = clientAssertion(UUID.randomUUID().toString(), Instant.now().plusSeconds(45));
        MvcResult issued = mockMvc.perform(post("/agent/api/v1/auth/token-exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exchangeJson(userLogin.accessToken(), assertion,
                                Set.of("catalog:read", "orders:self:read"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scopes.length()").value(2))
                .andReturn();
        String delegation = objectMapper.readTree(issued.getResponse().getContentAsString())
                .get("accessToken").asText();
        var validated = jwtTokenUtil.validate(delegation, IdentityType.AGENT_DELEGATION);
        assertThat(validated.authorizedParty()).isEqualTo("hotshop-agent-service");
        assertThat(validated.subjectUserId()).isEqualTo(userId);

        mockMvc.perform(post("/agent/api/v1/auth/token-exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exchangeJson(userLogin.accessToken(), assertion,
                                Set.of("catalog:read"))))
                .andExpect(status().isUnauthorized());

        IssuedAccessToken admin = jwtTokenUtil.issueAdministratorAccess(principal(
                adminId,
                ADMIN_USERNAME,
                "ROLE_ADMIN"
        ));
        mockMvc.perform(post("/agent/api/v1/auth/token-exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exchangeJson(
                                admin.value(),
                                clientAssertion(UUID.randomUUID().toString(), Instant.now().plusSeconds(45)),
                                Set.of("catalog:read")
                        )))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/agent/api/v1/auth/token-exchange")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(exchangeJson(
                                userLogin.accessToken(),
                                clientAssertion(UUID.randomUUID().toString(), Instant.now().plusSeconds(45)),
                                Set.of("users:write")
                        )))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("AGENT_SCOPE_NOT_ALLOWED"));
    }

    @Test
    @Order(7)
    void agentDelegationClaimsAndCrossBoundaryAreEnforced() throws Exception {
        String noAzp = signedAgentToken(
                Map.of("scope", "catalog:read"),
                "missing-azp"
        );
        mockMvc.perform(get("/agent/api/v1/anything")
                        .header(HttpHeaders.AUTHORIZATION, bearer(noAzp)))
                .andExpect(status().isUnauthorized());

        String excessiveScope = signedAgentToken(
                Map.of(
                        "scope", "users:write",
                        "azp", "hotshop-agent-service"
                ),
                "excessive-scope"
        );
        mockMvc.perform(get("/agent/api/v1/anything")
                        .header(HttpHeaders.AUTHORIZATION, bearer(excessiveScope)))
                .andExpect(status().isUnauthorized());

        String adminClaim = signedAgentToken(
                Map.of(
                        "scope", "catalog:read",
                        "azp", "hotshop-agent-service",
                        "authorities", List.of("ROLE_ADMIN")
                ),
                "admin-claim"
        );
        mockMvc.perform(get("/agent/api/v1/anything")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminClaim)))
                .andExpect(status().isUnauthorized());

        String valid = signedAgentToken(
                Map.of(
                        "scope", "catalog:read",
                        "azp", "hotshop-agent-service"
                ),
                "valid-agent"
        );
        mockMvc.perform(get("/agent/api/v1/anything")
                        .header(HttpHeaders.AUTHORIZATION, bearer(valid)))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/orders")
                        .header(HttpHeaders.AUTHORIZATION, bearer(valid)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @Order(8)
    void malformedAlgorithmKidIssuerAudienceTimesAndSignatureAreRejected() throws Exception {
        Instant now = Instant.now();
        String wrongIssuer = sign(
                KEYS.user.privateKey(),
                "user-local-1",
                "user-access+jwt",
                "https://wrong.hotshop.local",
                "hotshop-portal-api",
                Long.toString(userId),
                now,
                now,
                now.plusSeconds(300),
                "wrong-issuer",
                Map.of(
                        "token_use", "user_access",
                        "preferred_username", USERNAME,
                        "authorities", List.of("ROLE_USER")
                )
        );
        String wrongAudience = sign(
                KEYS.user.privateKey(),
                "user-local-1",
                "user-access+jwt",
                "https://auth.hotshop.local/user",
                "wrong-audience",
                Long.toString(userId),
                now,
                now,
                now.plusSeconds(300),
                "wrong-audience",
                Map.of(
                        "token_use", "user_access",
                        "preferred_username", USERNAME,
                        "authorities", List.of("ROLE_USER")
                )
        );
        String future = signedUserToken(
                now,
                now.plusSeconds(120),
                now.plusSeconds(300),
                "future",
                Map.of("authorities", List.of("ROLE_USER"))
        );
        String expired = signedUserToken(
                now.minusSeconds(120),
                now.minusSeconds(120),
                now.minusSeconds(60),
                "expired",
                Map.of("authorities", List.of("ROLE_USER"))
        );
        String valid = signedUserToken(
                now,
                now,
                now.plusSeconds(300),
                "valid-for-tamper",
                Map.of("authorities", List.of("ROLE_USER"))
        );
        String unknownKid = replaceHeader(valid, "kid", "unknown-key");
        String wrongAlgorithm = replaceHeader(valid, "alg", "none");
        String tampered = valid.substring(0, valid.length() - 2)
                + (valid.endsWith("aa") ? "bb" : "aa");

        for (String rejected : List.of(
                wrongIssuer,
                wrongAudience,
                future,
                expired,
                unknownKid,
                wrongAlgorithm,
                tampered
        )) {
            mockMvc.perform(get("/api/v1/orders")
                            .header(HttpHeaders.AUTHORIZATION, bearer(rejected)))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(not(containsString(rejected))));
        }
    }

    @Test
    @Order(9)
    void layeredFailureLimitReturnsRetryAfterAndSanitizedProblems() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson(USERNAME, "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().string(not(containsString("wrong-password"))));
        }
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(USERNAME, "wrong-password")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"));
    }

    @Test
    @Order(99)
    void redisFailureIsFailClosedForAuthenticationWrites() throws Exception {
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
        try {
            org.junit.jupiter.api.Assertions.assertTimeout(
                    java.time.Duration.ofSeconds(10),
                    () -> mockMvc.perform(post("/api/v1/auth/login")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(loginJson(USERNAME, PASSWORD)))
                            .andExpect(status().isServiceUnavailable())
                            .andExpect(jsonPath("$.code")
                                    .value("AUTHENTICATION_SERVICE_UNAVAILABLE"))
                            .andExpect(content().string(not(containsString(PASSWORD))))
            );
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
        }
    }

    @Test
    @Order(10)
    void sensitiveValuesAreAbsentFromLogsProblemsAuditAndOpenApi() throws Exception {
        String passwordMarker = "NeverLog-Password-Task05!";
        String authorizationMarker = "raw-authorization-task05-marker";
        Logger root = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        root.addAppender(appender);
        MvcResult loginFailure;
        MvcResult bearerFailure;
        MvcResult openApi;
        String audit;
        try {
            loginFailure = mockMvc.perform(post("/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("scan-user", passwordMarker)))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            bearerFailure = mockMvc.perform(get("/api/v1/orders")
                            .header(
                                    HttpHeaders.AUTHORIZATION,
                                    bearer(authorizationMarker)
                            ))
                    .andExpect(status().isUnauthorized())
                    .andReturn();
            openApi = mockMvc.perform(get("/v3/api-docs/agent-boundary"))
                    .andExpect(status().isOk())
                    .andReturn();

            audit = jdbcTemplate.queryForObject(
                    "SELECT COALESCE(GROUP_CONCAT(CAST(state_summary AS CHAR)), '') FROM audit_log",
                    String.class
            );
        } finally {
            root.detachAppender(appender);
            appender.stop();
        }
        List<ILoggingEvent> stableLogSnapshot = List.copyOf(appender.list);
        String logs = stableLogSnapshot.stream()
                .map(ILoggingEvent::getFormattedMessage)
                .reduce("", (left, right) -> left + "\n" + right);
        String problems = loginFailure.getResponse().getContentAsString()
                + bearerFailure.getResponse().getContentAsString();
        String contract = openApi.getResponse().getContentAsString();

        for (String sensitive : List.of(passwordMarker, authorizationMarker, "PRIVATE KEY")) {
            assertThat(logs).doesNotContain(sensitive);
            assertThat(problems).doesNotContain(sensitive);
            assertThat(audit).doesNotContain(sensitive);
            assertThat(contract).doesNotContain(sensitive);
        }
    }

    private LoginResult loginUser() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(USERNAME, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return fromTokenResponse(result, SessionTypeNames.USER);
    }

    private org.springframework.test.web.servlet.ResultActions refresh(
            String refreshToken,
            String csrfToken,
            String accessToken
    ) throws Exception {
        var request = post("/api/v1/auth/refresh")
                .cookie(
                        new Cookie(RefreshCookieService.USER_REFRESH_COOKIE, refreshToken),
                        new Cookie(RefreshCookieService.USER_CSRF_COOKIE, csrfToken)
                )
                .header(RefreshCookieService.CSRF_HEADER, csrfToken);
        if (accessToken != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer(accessToken));
        }
        return mockMvc.perform(request);
    }

    private LoginResult fromTokenResponse(MvcResult result, SessionTypeNames type) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        List<String> setCookies = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE);
        String refreshHeader = setCookies.stream()
                .filter(value -> value.startsWith(type.refreshName() + "="))
                .findFirst()
                .orElseThrow();
        String csrfHeader = setCookies.stream()
                .filter(value -> value.startsWith(type.csrfName() + "="))
                .findFirst()
                .orElseThrow();
        return new LoginResult(
                result,
                json.get("accessToken").asText(),
                cookieValue(refreshHeader),
                cookieValue(csrfHeader),
                refreshHeader,
                csrfHeader
        );
    }

    private String exchangeJson(String subject, String assertion, Set<String> scopes) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "subjectToken", subject,
                "clientAssertion", assertion,
                "scopes", scopes
        ));
    }

    private String loginJson(String username, String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of("username", username, "password", password));
    }

    private String clientAssertion(String jti, Instant expiresAt) {
        Instant now = Instant.now();
        return sign(
                KEYS.agentService.privateKey(),
                "agent-service-local-1",
                "client-auth+jwt",
                "https://agent.hotshop.local/service",
                "hotshop-agent-token-exchange",
                "hotshop-agent-service",
                now,
                now,
                expiresAt,
                jti,
                Map.of()
        );
    }

    private String signedUserToken(
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            String jti,
            Map<String, Object> extra
    ) {
        java.util.LinkedHashMap<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("token_use", "user_access");
        claims.put("preferred_username", USERNAME);
        claims.putAll(extra);
        claims.remove("overrideIssuer");
        return sign(
                KEYS.user.privateKey(),
                "user-local-1",
                "user-access+jwt",
                "https://auth.hotshop.local/user",
                "hotshop-portal-api",
                Long.toString(userId),
                issuedAt,
                notBefore,
                expiresAt,
                jti,
                claims
        );
    }

    private String signedAgentToken(Map<String, Object> extra, String jti) {
        Instant now = Instant.now();
        java.util.LinkedHashMap<String, Object> claims = new java.util.LinkedHashMap<>();
        claims.put("token_use", "agent_delegation");
        claims.put("preferred_username", USERNAME);
        claims.putAll(extra);
        return sign(
                KEYS.agentDelegation.privateKey(),
                "agent-delegation-local-1",
                "agent-delegation+jwt",
                "https://auth.hotshop.local/agent-delegation",
                "hotshop-agent-api",
                Long.toString(userId),
                now,
                now,
                now.plusSeconds(240),
                jti,
                claims
        );
    }

    private String sign(
            PrivateKey key,
            String kid,
            String typ,
            String issuer,
            String audience,
            String subject,
            Instant issuedAt,
            Instant notBefore,
            Instant expiresAt,
            String jti,
            Map<String, Object> claims
    ) {
        return Jwts.builder()
                .setHeaderParam("kid", kid)
                .setHeaderParam("typ", typ)
                .setClaims(claims)
                .setIssuer(issuer)
                .setAudience(audience)
                .setSubject(subject)
                .setIssuedAt(Date.from(issuedAt))
                .setNotBefore(Date.from(notBefore))
                .setExpiration(Date.from(expiresAt))
                .setId(jti)
                .signWith(key, SignatureAlgorithm.RS256)
                .compact();
    }

    private String replaceHeader(String token, String name, String value) throws Exception {
        String[] parts = token.split("\\.");
        @SuppressWarnings("unchecked")
        Map<String, Object> header = objectMapper.readValue(
                Base64.getUrlDecoder().decode(parts[0]),
                Map.class
        );
        header.put(name, value);
        parts[0] = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(objectMapper.writeValueAsBytes(header));
        return String.join(".", parts);
    }

    private CustomUserDetails principal(long id, String username, String authority) {
        return CustomUserDetails.builder()
                .userId(id)
                .username(username)
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority(authority)))
                .build();
    }

    private String sha256(String value) throws Exception {
        return java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256")
                        .digest(value.getBytes(StandardCharsets.UTF_8))
        );
    }

    private String cookieValue(String setCookie) {
        return setCookie.substring(setCookie.indexOf('=') + 1, setCookie.indexOf(';'));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginResult(
            MvcResult response,
            String accessToken,
            String refreshToken,
            String csrfToken,
            String refreshSetCookie,
            String csrfSetCookie
    ) {
    }

    private enum SessionTypeNames {
        USER(
                RefreshCookieService.USER_REFRESH_COOKIE,
                RefreshCookieService.USER_CSRF_COOKIE
        );

        private final String refreshName;
        private final String csrfName;

        SessionTypeNames(String refreshName, String csrfName) {
            this.refreshName = refreshName;
            this.csrfName = csrfName;
        }

        String refreshName() {
            return refreshName;
        }

        String csrfName() {
            return csrfName;
        }
    }

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
                Path directory = Files.createTempDirectory("hotshop-task05-keys-");
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
            addDomain(registry, "user", "user-local-1", user, true);
            addDomain(registry, "administrator", "administrator-local-1", administrator, true);
            addDomain(registry, "agent-delegation", "agent-delegation-local-1", agentDelegation, true);
            registry.add(
                    "hotshop.security.client-assertion.verification-key-paths.agent-service-local-1",
                    () -> agentService.publicPath().toString()
            );
        }

        private void addDomain(
                DynamicPropertyRegistry registry,
                String domain,
                String kid,
                KeyMaterial material,
                boolean signing
        ) {
            if (signing) {
                registry.add(
                        "hotshop.security." + domain + ".private-key-path",
                        () -> material.privatePath().toString()
                );
            }
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
