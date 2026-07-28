package com.real.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.Base64;
import java.util.List;
import java.util.Map;

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
            .withPassword("hotshop-test");
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
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
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

    private LoginResult loginAdmin() throws Exception {
        MvcResult result = mockMvc.perform(post("/admin/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(loginJson(PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        return parse(result);
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
