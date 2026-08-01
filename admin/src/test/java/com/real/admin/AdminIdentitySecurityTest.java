package com.real.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.admin.service.AdminProductAuditService;
import com.real.common.api.RequestContext;
import com.real.domain.entity.Product;
import com.real.security.entity.CustomUserDetails;
import com.real.security.identity.IdentityType;
import com.real.security.identity.IssuedAccessToken;
import com.real.security.service.RefreshCookieService;
import com.real.security.util.JwtTokenUtil;
import jakarta.servlet.http.Cookie;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.math.BigDecimal;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "rabbitmq.enabled=false",
        "spring.task.scheduling.enabled=false",
        "hotshop.security.refresh.secure-cookie=false",
        "hotshop.security.rate-limit.administrator-login-ip.limit=100",
        "hotshop.security.rate-limit.administrator-login-identity.limit=100",
        "hotshop.security.rate-limit.administrator-login-failure.limit=2",
        "hotshop.security.rate-limit.administrator-refresh.limit=100"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminIdentitySecurityTest {
    private static final String ADMIN_USERNAME = "task05-real-admin";
    private static final String USERNAME = "task05-real-user";
    private static final String PASSWORD = "Task05-Admin-Password!";

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotShop")
            .withUsername("hotshop")
            .withPassword("hotshop-test")
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
    AdminProductAuditService productAuditService;

    long adminId;
    long userId;

    @BeforeAll
    void migrateAndSeed() {
        Flyway flyway = Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load();
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(5);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
        String hash = new BCryptPasswordEncoder().encode(PASSWORD);
        jdbcTemplate.update(
                """
                INSERT INTO app_user (username, password_hash, email, role, status)
                VALUES (?, ?, 'task05-real-admin@hotshop.invalid', 'ROLE_ADMIN', 'ACTIVE'),
                       (?, ?, 'task05-real-user@hotshop.invalid', 'ROLE_USER', 'ACTIVE')
                """,
                ADMIN_USERNAME,
                hash,
                USERNAME,
                hash
        );
        adminId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM app_user WHERE username = ?",
                Long.class,
                ADMIN_USERNAME
        );
        userId = jdbcTemplate.queryForObject(
                "SELECT user_id FROM app_user WHERE username = ?",
                Long.class,
                USERNAME
        );
    }

    @AfterAll
    void stopContainersAndDeleteKeys() {
        KEYS.delete();
        REDIS.stop();
        MYSQL.stop();
    }

    @Test
    void administratorLoginHasIndependentAudienceCookieAndPermissions() throws Exception {
        LoginResult login = loginAdmin();
        var validated = jwtTokenUtil.validate(
                login.accessToken(),
                IdentityType.ADMINISTRATOR_ACCESS
        );
        assertThat(validated.subjectUserId()).isEqualTo(adminId);
        assertThat(login.refreshSetCookie())
                .contains("HttpOnly")
                .contains("SameSite=Strict")
                .contains("Path=/admin/api/v1/auth")
                .doesNotContain("Secure")
                .doesNotContain(RefreshCookieService.USER_REFRESH_COOKIE);

        mockMvc.perform(get("/admin/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(login.accessToken())))
                .andExpect(status().isOk());
        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*) FROM refresh_token
                WHERE user_id = ? AND session_type = 'ADMIN'
                  AND issuer = 'https://auth.hotshop.local/administrator'
                  AND audience = 'hotshop-admin-api'
                """,
                Integer.class,
                adminId
        )).isPositive();
    }

    @Test
    void administratorRefreshAndLogoutUseCsrfAndIndependentFamily() throws Exception {
        LoginResult login = loginAdmin();
        MvcResult refreshed = mockMvc.perform(post("/admin/api/v1/auth/refresh")
                        .cookie(
                                new Cookie(RefreshCookieService.ADMIN_REFRESH_COOKIE, login.refreshToken()),
                                new Cookie(RefreshCookieService.ADMIN_CSRF_COOKIE, login.csrfToken())
                        )
                        .header(RefreshCookieService.CSRF_HEADER, login.csrfToken()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-store"))
                .andReturn();
        LoginResult successor = parse(refreshed);

        mockMvc.perform(post("/admin/api/v1/auth/logout")
                        .cookie(
                                new Cookie(RefreshCookieService.ADMIN_REFRESH_COOKIE, successor.refreshToken()),
                                new Cookie(RefreshCookieService.ADMIN_CSRF_COOKIE, successor.csrfToken())
                        )
                        .header(RefreshCookieService.CSRF_HEADER, successor.csrfToken())
                        .header(HttpHeaders.AUTHORIZATION, bearer(successor.accessToken())))
                .andExpect(status().isOk());

        mockMvc.perform(get("/admin/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(successor.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void realFilterRejectsUserAtAdminAndAdminAtPortalBoundaries() throws Exception {
        LoginResult admin = loginAdmin();
        CustomUserDetails userPrincipal = CustomUserDetails.builder()
                .userId(userId)
                .username(USERNAME)
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        IssuedAccessToken user = jwtTokenUtil.issueUserAccess(userPrincipal);

        mockMvc.perform(get("/admin/api/v1/users")
                        .header(HttpHeaders.AUTHORIZATION, bearer(user.value())))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/products")
                        .header(HttpHeaders.AUTHORIZATION, bearer(admin.accessToken())))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void administratorFailureBucketReturnsRetryAfterWithoutCredentialLeak() throws Exception {
        for (int attempt = 0; attempt < 2; attempt++) {
            mockMvc.perform(post("/admin/api/v1/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(loginJson("wrong")))
                    .andExpect(status().isUnauthorized());
        }
        mockMvc.perform(post("/admin/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson("wrong")))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists(HttpHeaders.RETRY_AFTER))
                .andExpect(jsonPath("$.code").value("RATE_LIMITED"))
                .andExpect(content().string(not(containsString("wrong"))));
    }

    @Test
    void auditQueryFiltersAndPaginatesWithStableDescendingCompositeOrder() throws Exception {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 7, 28, 12, 0, 0, 123_456_000);
        long firstId = insertAudit("audit-page-1", "7001", occurredAt);
        long secondId = insertAudit("audit-page-2", "7002", occurredAt);
        long thirdId = insertAudit("audit-page-3", "7003", occurredAt);

        MvcResult firstPage = mockMvc.perform(get("/admin/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminAccess()))
                        .param("limit", "2")
                        .param("occurredFrom", "2026-07-28T11:59:59Z")
                        .param("occurredTo", "2026-07-28T12:00:01Z")
                        .param("actorType", "ADMIN")
                        .param("actorId", "audit-query-admin")
                        .param("action", "CATALOG_PRODUCT_UPDATED")
                        .param("resourceType", "CATALOG_PRODUCT")
                        .param("result", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].auditId").value(Long.toString(thirdId)))
                .andExpect(jsonPath("$.items[1].auditId").value(Long.toString(secondId)))
                .andExpect(jsonPath("$.items[0].source").value("ADMIN_API"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").isNotEmpty())
                .andReturn();
        String cursor = objectMapper.readTree(firstPage.getResponse().getContentAsString())
                .get("nextCursor")
                .asText();

        mockMvc.perform(get("/admin/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminAccess()))
                        .param("limit", "2")
                        .param("cursor", cursor)
                        .param("occurredFrom", "2026-07-28T11:59:59Z")
                        .param("occurredTo", "2026-07-28T12:00:01Z")
                        .param("actorType", "ADMIN")
                        .param("actorId", "audit-query-admin")
                        .param("action", "CATALOG_PRODUCT_UPDATED")
                        .param("resourceType", "CATALOG_PRODUCT")
                        .param("result", "SUCCESS"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].auditId").value(Long.toString(firstId)))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.hasMore").value(false));

        mockMvc.perform(get("/admin/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminAccess()))
                        .param("cursor", cursor)
                        .param("occurredFrom", "2026-07-28T11:59:59Z")
                        .param("occurredTo", "2026-07-28T12:00:01Z")
                        .param("actorType", "ADMIN")
                        .param("actorId", "audit-query-admin")
                        .param("action", "CATALOG_PRODUCT_UPDATED")
                        .param("resourceType", "CATALOG_PRODUCT")
                        .param("result", "FAILURE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("CURSOR_INVALID"));

        mockMvc.perform(get("/admin/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminAccess()))
                        .param("resourceType", "CATALOG_PRODUCT")
                        .param("resourceId", "7002"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].auditId").value(Long.toString(secondId)))
                .andExpect(jsonPath("$.items.length()").value(1));
    }

    @Test
    void onlyAdministratorAccessCanReadAuditLogs() throws Exception {
        CustomUserDetails userPrincipal = CustomUserDetails.builder()
                .userId(userId)
                .username(USERNAME)
                .password("")
                .authorities(List.of(new SimpleGrantedAuthority("ROLE_USER")))
                .build();
        String userAccess = jwtTokenUtil.issueUserAccess(userPrincipal).value();
        String agentDelegation = jwtTokenUtil.issueAgentDelegation(
                userId,
                USERNAME,
                "hotshop-agent-service",
                Set.of("catalog:read")
        ).value();

        mockMvc.perform(get("/admin/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(adminAccess())))
                .andExpect(status().isOk());
        mockMvc.perform(get("/admin/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(userAccess)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(agentDelegation)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/admin/api/v1/audit-logs"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void auditBusinessApiExposesNoMutationOperations() throws Exception {
        String access = adminAccess();
        mockMvc.perform(delete("/admin/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(access)))
                .andExpect(status().isMethodNotAllowed());
        mockMvc.perform(put("/admin/api/v1/audit-logs")
                        .header(HttpHeaders.AUTHORIZATION, bearer(access))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isMethodNotAllowed());
    }

    @Test
    void successfulAdminWriteAndAuditCommitTogetherWithMinimizedSummary() {
        MockHttpServletRequest request = auditRequest(
                "admin-product-success",
                "11111111111111111111111111111111"
        );
        Product product = new Product(
                null,
                "Audited product success",
                new BigDecimal("25.00"),
                4,
                "Audit",
                "password=NeverPersist Authorization=Bearer NeverPersist fullPrompt=NeverPersist",
                null
        );

        Product created = productAuditService.create(product, adminId, request);

        assertThat(created.getProductId()).isPositive();
        String summary = jdbcTemplate.queryForObject(
                """
                SELECT state_summary
                FROM audit_log
                WHERE request_id = 'admin-product-success'
                  AND action = 'CATALOG_PRODUCT_CREATED'
                  AND result = 'SUCCESS'
                """,
                String.class
        );
        assertThat(summary)
                .contains("changedFields")
                .doesNotContain("NeverPersist")
                .doesNotContain("password")
                .doesNotContain("Authorization")
                .doesNotContain("fullPrompt");
    }

    @Test
    void businessFailureIsAuditedInIndependentTransaction() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_catalog_product_insert_failure");
        jdbcTemplate.execute("""
                CREATE TRIGGER test_catalog_product_insert_failure
                BEFORE INSERT ON catalog_product
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced product failure'
                """);
        try {
            Product product = new Product(
                    null,
                    "Audited product failure",
                    new BigDecimal("10.00"),
                    1,
                    "Audit",
                    null,
                    null
            );
            MockHttpServletRequest request = auditRequest(
                    "admin-product-failure",
                    "22222222222222222222222222222222"
            );

            assertThatThrownBy(() -> productAuditService.create(product, adminId, request))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_catalog_product_insert_failure");
        }

        assertThat(jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM audit_log
                WHERE request_id = 'admin-product-failure'
                  AND action = 'CATALOG_PRODUCT_CREATED'
                  AND result = 'FAILURE'
                  AND JSON_UNQUOTE(JSON_EXTRACT(state_summary, '$.reasonCode')) = 'OPERATION_FAILED'
                """,
                Integer.class
        )).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_product WHERE name = 'Audited product failure'",
                Integer.class
        )).isZero();
    }

    @Test
    void unavailableAuditStorageRollsBackHighRiskAdminWrite() {
        jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_audit_log_insert_failure");
        jdbcTemplate.execute("""
                CREATE TRIGGER test_audit_log_insert_failure
                BEFORE INSERT ON audit_log
                FOR EACH ROW
                SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'forced audit failure'
                """);
        try {
            Product product = new Product(
                    null,
                    "Must roll back without audit",
                    new BigDecimal("15.00"),
                    2,
                    "Audit",
                    null,
                    null
            );
            MockHttpServletRequest request = auditRequest(
                    "admin-audit-unavailable",
                    "33333333333333333333333333333333"
            );

            assertThatThrownBy(() -> productAuditService.create(product, adminId, request))
                    .isInstanceOf(RuntimeException.class);
        } finally {
            jdbcTemplate.execute("DROP TRIGGER IF EXISTS test_audit_log_insert_failure");
        }

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_product WHERE name = 'Must roll back without audit'",
                Integer.class
        )).isZero();
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE request_id = 'admin-audit-unavailable'",
                Integer.class
        )).isZero();
    }

    private LoginResult loginAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return parse(result);
    }

    private String adminAccess() {
        CustomUserDetails principal = CustomUserDetails.builder()
                .userId(adminId)
                .username(ADMIN_USERNAME)
                .password("")
                .authorities(
                        JwtTokenUtil.administratorAuthorities().stream()
                                .map(SimpleGrantedAuthority::new)
                                .toList()
                )
                .build();
        return jwtTokenUtil.issueAdministratorAccess(principal).value();
    }

    private long insertAudit(String requestId, String resourceId, LocalDateTime occurredAt) {
        jdbcTemplate.update(
                """
                INSERT INTO audit_log (
                    occurred_at, actor_type, actor_id, action, resource_type, resource_id,
                    result, request_id, trace_id, source, state_summary
                ) VALUES (?, 'ADMIN', 'audit-query-admin', 'CATALOG_PRODUCT_UPDATED',
                    'CATALOG_PRODUCT', ?, 'SUCCESS', ?,
                    '44444444444444444444444444444444', 'ADMIN_API',
                    JSON_OBJECT('changedFields', JSON_ARRAY('stock')))
                """,
                Timestamp.valueOf(occurredAt),
                resourceId,
                requestId
        );
        return jdbcTemplate.queryForObject(
                "SELECT audit_id FROM audit_log WHERE request_id = ?",
                Long.class,
                requestId
        );
    }

    private MockHttpServletRequest auditRequest(String requestId, String traceId) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(RequestContext.REQUEST_ID_ATTRIBUTE, requestId);
        request.setAttribute(RequestContext.TRACE_ID_ATTRIBUTE, traceId);
        return request;
    }

    private LoginResult parse(MvcResult result) throws Exception {
        JsonNode json = objectMapper.readTree(result.getResponse().getContentAsString());
        String refresh = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(RefreshCookieService.ADMIN_REFRESH_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        String csrf = result.getResponse().getHeaders(HttpHeaders.SET_COOKIE).stream()
                .filter(value -> value.startsWith(RefreshCookieService.ADMIN_CSRF_COOKIE + "="))
                .findFirst()
                .orElseThrow();
        return new LoginResult(
                json.get("accessToken").asText(),
                cookieValue(refresh),
                cookieValue(csrf),
                refresh
        );
    }

    private String loginJson(String password) throws Exception {
        return objectMapper.writeValueAsString(Map.of(
                "username", ADMIN_USERNAME,
                "password", password
        ));
    }

    private String cookieValue(String header) {
        return header.substring(header.indexOf('=') + 1, header.indexOf(';'));
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private record LoginResult(
            String accessToken,
            String refreshToken,
            String csrfToken,
            String refreshSetCookie
    ) {
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
                Path directory = Files.createTempDirectory("hotshop-task05-admin-keys-");
                return new TestKeys(
                        directory,
                        create(directory, "user"),
                        create(directory, "administrator"),
                        create(directory, "agent-delegation"),
                        create(directory, "agent-service")
                );
            } catch (Exception exception) {
                throw new ExceptionInInitializerError(exception);
            }
        }

        void register(DynamicPropertyRegistry registry) {
            add(registry, "user", "user-local-1", user);
            add(registry, "administrator", "administrator-local-1", administrator);
            add(registry, "agent-delegation", "agent-delegation-local-1", agentDelegation);
            registry.add(
                    "hotshop.security.client-assertion.verification-key-paths.agent-service-local-1",
                    () -> agentService.publicPath().toString()
            );
        }

        private void add(
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

        void delete() {
            try {
                for (KeyMaterial material : List.of(
                        user, administrator, agentDelegation, agentService
                )) {
                    Files.deleteIfExists(material.privatePath());
                    Files.deleteIfExists(material.publicPath());
                }
                Files.deleteIfExists(directory);
            } catch (Exception ignored) {
                // The system temp directory is the recovery boundary.
            }
        }

        private static KeyMaterial create(Path directory, String name) throws Exception {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            KeyPair pair = generator.generateKeyPair();
            Path privatePath = directory.resolve(name + "-private.pem");
            Path publicPath = directory.resolve(name + "-public.pem");
            Files.writeString(privatePath, pem("PRIVATE KEY", pair.getPrivate().getEncoded()));
            Files.writeString(publicPath, pem("PUBLIC KEY", pair.getPublic().getEncoded()));
            return new KeyMaterial(privatePath, publicPath);
        }

        private static String pem(String label, byte[] bytes) {
            return "-----BEGIN " + label + "-----\n"
                    + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(bytes)
                    + "\n-----END " + label + "-----\n";
        }
    }

    private record KeyMaterial(Path privatePath, Path publicPath) {
    }
}
