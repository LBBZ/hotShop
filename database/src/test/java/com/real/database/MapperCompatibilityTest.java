package com.real.database;

import com.real.common.enums.OrderStatus;
import com.real.common.enums.Role;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.Order;
import com.real.domain.entity.OrderItem;
import com.real.domain.entity.Product;
import com.real.domain.entity.User;
import com.real.domain.mapper.OrderMapper;
import com.real.domain.mapper.ProductMapper;
import com.real.domain.mapper.UserMapper;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.exceptions.PersistenceException;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.ibatis.type.EnumTypeHandler;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.sql.DataSource;
import java.io.InputStream;
import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MapperCompatibilityTest {

    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withDatabaseName("hotshop_mapper")
            .withUsername("hotshop")
            .withPassword("hotshop-test")
            .withEnv("TZ", "UTC")
            .withCommand(
                    "--default-time-zone=+00:00",
                    "--log-bin-trust-function-creators=1"
            )
            .withUrlParam("serverTimezone", "UTC")
            .withUrlParam("connectionTimeZone", "UTC")
            .withUrlParam("forceConnectionTimeZoneToSession", "true");

    private static SqlSessionFactory sessionFactory;

    @BeforeAll
    static void prepareDatabaseAndMappers() throws Exception {
        Flyway.configure()
                .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
                .locations("classpath:db/migration")
                .validateMigrationNaming(true)
                .cleanDisabled(true)
                .load()
                .migrate();

        DataSource dataSource = new org.apache.ibatis.datasource.unpooled.UnpooledDataSource(
                MYSQL.getDriverClassName(), MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()
        );
        Configuration configuration = new Configuration(
                new Environment("test", new JdbcTransactionFactory(), dataSource)
        );
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setDefaultEnumTypeHandler(EnumTypeHandler.class);
        configuration.getTypeAliasRegistry().registerAlias("User", User.class);
        configuration.getTypeAliasRegistry().registerAlias("Product", Product.class);
        configuration.getTypeAliasRegistry().registerAlias("Order", Order.class);
        configuration.getTypeAliasRegistry().registerAlias("OrderItem", OrderItem.class);

        parseMapper(configuration, "com/real/domain/mapper/UserMapper.xml");
        parseMapper(configuration, "com/real/domain/mapper/ProductMapper.xml");
        parseMapper(configuration, "com/real/domain/mapper/OrderMapper.xml");
        sessionFactory = new SqlSessionFactoryBuilder().build(configuration);
    }

    @Test
    void existingMapperContractsWorkAgainstRenamedTables() {
        try (SqlSession session = sessionFactory.openSession(false)) {
            UserMapper users = session.getMapper(UserMapper.class);
            ProductMapper products = session.getMapper(ProductMapper.class);
            OrderMapper orders = session.getMapper(OrderMapper.class);

            User user = User.builder()
                    .username("mapper-user")
                    .password("mapper-hash")
                    .email("mapper-user@hotshop.invalid")
                    .role(Role.ROLE_USER)
                    .build();
            assertThat(users.insert(user)).isEqualTo(1);
            assertThat(user.getUserId()).isPositive();
            assertThat(users.selectByUsername("mapper-user").getPassword()).isEqualTo("mapper-hash");

            Product product = new Product(
                    null, "Mapper product", new BigDecimal("20.00"), 5,
                    "Mapper", "compatibility", null
            );
            assertThat(products.insert(product)).isEqualTo(1);
            assertThat(product.getProductId()).isPositive();
            assertThat(products.reduceStock(product.getProductId(), -1)).isZero();
            assertThat(products.reduceStock(product.getProductId(), 2)).isEqualTo(1);
            assertThat(products.selectById(product.getProductId()).getStock()).isEqualTo(3);

            Order order = new Order(
                    "mapper-order", user.getUserId(), new BigDecimal("40.00"),
                    OrderStatus.PENDING, null, null
            );
            assertThat(orders.insertOrder(order)).isEqualTo(1);
            OrderItem item = new OrderItem(
                    null, order.getOrderId(), product.getProductId(), 2, new BigDecimal("20.00")
            );
            assertThat(orders.insertOrderItem(item)).isEqualTo(1);

            Order loaded = orders.selectOrderById(order.getOrderId());
            assertThat(loaded.getStatus()).isEqualTo(OrderStatus.PENDING);
            assertThat(loaded.getItems()).extracting(OrderItem::getProductId)
                    .containsExactly(product.getProductId());
            assertThat(orders.selectOrdersByUserId(user.getUserId(), 0, 10, 0, 10))
                    .extracting(Order::getOrderId)
                    .containsExactly("mapper-order");

            assertThat(products.delete(product.getProductId())).isEqualTo(1);
            assertThat(products.selectById(product.getProductId())).isNull();
            session.commit();
        }
    }

    @Test
    void softDeletedUserIdentifiersRemainReservedButUserIsNotVisible() {
        User original = user("reserved-user", "reserved-user@hotshop.invalid");
        try (SqlSession session = sessionFactory.openSession(false)) {
            UserMapper users = session.getMapper(UserMapper.class);
            assertThat(users.insert(original)).isEqualTo(1);
            session.commit();
        }

        try (SqlSession session = sessionFactory.openSession(false)) {
            UserMapper users = session.getMapper(UserMapper.class);
            assertThat(users.delete(original.getUserId())).isEqualTo(1);
            session.commit();
        }

        try (SqlSession session = sessionFactory.openSession()) {
            UserMapper users = session.getMapper(UserMapper.class);
            assertThat(users.existsByUsername(original.getUsername())).isTrue();
            assertThat(users.existsByEmail(original.getEmail())).isTrue();
            assertThat(users.selectByUsername(original.getUsername())).isNull();
            assertThat(users.selectAll())
                    .extracting(User::getUsername)
                    .doesNotContain(original.getUsername());
        }

        assertRegistrationRejected(user("reserved-user", "another-user@hotshop.invalid"));
        assertRegistrationRejected(user("another-reserved-user", "reserved-user@hotshop.invalid"));
    }

    @Test
    void orderKeysetPaginationIsStableForEqualSortTimesAndConcurrentInsertion() throws Exception {
        LocalDateTime createdAt = LocalDateTime.of(2026, 7, 27, 10, 15, 30, 123_456_000);
        try (SqlSession session = sessionFactory.openSession(false)) {
            insertOrders(session, createdAt, "contract-order-A", "contract-order-B", "contract-order-C");
            session.commit();
        }

        List<Order> firstPage;
        try (SqlSession session = sessionFactory.openSession()) {
            firstPage = session.getMapper(OrderMapper.class).selectOrdersByCursor(
                    987654321L, null, null, null, null, null, 2
            );
        }
        assertThat(firstPage).extracting(Order::getOrderId)
                .containsExactly("contract-order-C", "contract-order-B");

        try (SqlSession session = sessionFactory.openSession(false)) {
            insertOrders(session, createdAt, "contract-order-D");
            session.commit();
        }

        try (SqlSession session = sessionFactory.openSession()) {
            List<Order> secondPage = session.getMapper(OrderMapper.class).selectOrdersByCursor(
                    987654321L,
                    null,
                    null,
                    null,
                    createdAt,
                    "contract-order-B",
                    2
            );
            assertThat(secondPage).extracting(Order::getOrderId)
                    .containsExactly("contract-order-A");
        }
    }

    @Test
    void utcDatabaseMapperDtoAndTimeFiltersPreserveTheSameInstant() throws Exception {
        LocalDateTime storedUtc = LocalDateTime.of(2026, 7, 28, 8, 30, 15, 123_456_000);
        try (SqlSession session = sessionFactory.openSession(false);
             Statement statement = session.getConnection().createStatement();
             ResultSet resultSet = statement.executeQuery("""
                     SELECT @@session.time_zone,
                            ABS(TIMESTAMPDIFF(SECOND, NOW(6), UTC_TIMESTAMP(6)))
                     """)) {
            assertThat(resultSet.next()).isTrue();
            assertThat(resultSet.getString(1)).isEqualTo("+00:00");
            assertThat(resultSet.getInt(2)).isZero();
            insertOrders(session, storedUtc, "utc-contract-order");
            session.commit();
        }

        try (SqlSession session = sessionFactory.openSession()) {
            List<Order> filtered = session.getMapper(OrderMapper.class).selectOrdersByCursor(
                    987654321L,
                    null,
                    storedUtc,
                    storedUtc,
                    null,
                    null,
                    10
            );

            assertThat(filtered).extracting(Order::getOrderId)
                    .containsExactly("utc-contract-order");
            Instant expected = storedUtc.toInstant(ZoneOffset.UTC);
            assertThat(ApiDtoMapper.toOrderResponse(filtered.getFirst()).createdAt())
                    .isEqualTo(expected);
            assertThat(ApiDtoMapper.toUtcLocalDateTime(expected))
                    .isEqualTo(storedUtc);
        }
    }

    private static User user(String username, String email) {
        return User.builder()
                .username(username)
                .password("mapper-hash")
                .email(email)
                .role(Role.ROLE_USER)
                .build();
    }

    private static void assertRegistrationRejected(User user) {
        try (SqlSession session = sessionFactory.openSession(false)) {
            UserMapper users = session.getMapper(UserMapper.class);
            assertThatThrownBy(() -> users.insert(user))
                    .isInstanceOf(PersistenceException.class);
            session.rollback();
        }
    }

    private static void insertOrders(
            SqlSession session,
            LocalDateTime createdAt,
            String... orderIds
    ) throws Exception {
        try (PreparedStatement statement = session.getConnection().prepareStatement(
                "INSERT INTO sales_order "
                        + "(order_id, user_id, total_amount, status, created_at, updated_at) "
                        + "VALUES (?, 987654321, 1.00, 'PENDING', ?, ?)"
        )) {
            for (String orderId : orderIds) {
                statement.setString(1, orderId);
                // Bind the UTC database fact as LocalDateTime, matching MyBatis'
                // LocalDateTimeTypeHandler instead of applying the host JVM zone.
                statement.setObject(2, createdAt);
                statement.setObject(3, createdAt);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private static void parseMapper(Configuration configuration, String resource) throws Exception {
        try (InputStream input = Resources.getResourceAsStream(resource)) {
            new XMLMapperBuilder(input, configuration, resource, configuration.getSqlFragments()).parse();
        }
    }
}
