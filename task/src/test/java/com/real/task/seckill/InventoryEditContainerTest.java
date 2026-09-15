package com.real.task.seckill;

import com.real.domain.entity.Product;
import com.real.domain.mapper.ProductMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.*;
import org.mybatis.spring.SqlSessionFactoryBean;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;
import java.math.BigDecimal;
import static org.assertj.core.api.Assertions.assertThat;

class InventoryEditContainerTest {
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.0.46")
            .withCommand("--log-bin-trust-function-creators=1");
    static JdbcTemplate jdbc;
    static ProductMapper mapper;
    @BeforeAll static void setup() throws Exception {
        MYSQL.start();
        var source = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        Flyway.configure().dataSource(source).locations("classpath:db/migration").target("1.8").load().migrate();
        jdbc = new JdbcTemplate(source);
        jdbc.update("INSERT INTO catalog_product (sku,name,price,stock) VALUES ('pre-upgrade','baseline',10,37)");
        jdbc.update("""
                INSERT INTO flash_sale_activity(activity_code,product_id,sale_price,total_stock,available_stock,starts_at,ends_at)
                VALUES ('pre-upgrade',1,10,19,17,'2026-01-01','2027-01-01')
                """);
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        var factory = new SqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setTypeAliasesPackage("com.real.domain.entity");
        factory.setMapperLocations(new ClassPathResource("com/real/domain/mapper/ProductMapper.xml"));
        var config = new org.apache.ibatis.session.Configuration();
        config.setMapUnderscoreToCamelCase(true);
        factory.setConfiguration(config);
        mapper = new SqlSessionTemplate(factory.getObject()).getMapper(ProductMapper.class);
    }
    @AfterAll static void stop() { MYSQL.stop(); }
    @Test void migrationBaselinesExistingRowsAndDefaultsOnlyApplyToNewRows() {
        assertThat(jdbc.queryForObject("SELECT expected_stock FROM catalog_product WHERE sku='pre-upgrade'", Long.class)).isEqualTo(37L);
        assertThat(jdbc.queryForObject("SELECT expected_available_stock FROM flash_sale_activity WHERE activity_code='pre-upgrade'", Long.class)).isEqualTo(17L);
        jdbc.update("UPDATE catalog_product SET stock=36 WHERE sku='pre-upgrade'");
        jdbc.update("UPDATE flash_sale_activity SET available_stock=16 WHERE activity_code='pre-upgrade'");
        assertThat(jdbc.queryForObject("SELECT stock-expected_stock FROM catalog_product WHERE sku='pre-upgrade'", Long.class)).isEqualTo(-1L);
        assertThat(jdbc.queryForObject("SELECT available_stock-expected_available_stock FROM flash_sale_activity WHERE activity_code='pre-upgrade'", Long.class)).isEqualTo(-1L);
    }

    @Test void repeatedDevelopmentSeedMaintainsBalancesAndVersionsWithoutMaskingTampering() {
        var seed = new org.springframework.jdbc.datasource.init.ResourceDatabasePopulator(
                new org.springframework.core.io.FileSystemResource("../database/data/dev-data.sql"));
        seed.setSqlScriptEncoding("UTF-8");
        seed.execute(jdbc.getDataSource());
        mapper.reduceStock(900001L,1);
        seed.execute(jdbc.getDataSource());
        assertThat(mapper.selectById(900001L).getStock()).isEqualTo(100);
        assertThat(mapper.selectById(900001L).getVersion()).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT stock-expected_stock FROM catalog_product WHERE product_id=900001", Long.class)).isZero();
        jdbc.update("UPDATE catalog_product SET stock=99 WHERE product_id=900001");
        seed.execute(jdbc.getDataSource());
        assertThat(jdbc.queryForObject("SELECT stock-expected_stock FROM catalog_product WHERE product_id=900001", Long.class)).isEqualTo(-1L);
    }

    @Test void concurrentAdjustmentsOnlyCommitOneVersionAndPreserveIndependentBalance() throws Exception {
        Product product = new Product(null,"adjust",new BigDecimal("10.00"),100,"test","description",null);
        mapper.insert(product);
        var repository = new com.real.domain.adminops.AdminProductMutationRepository(jdbc);
        var ready = new java.util.concurrent.CountDownLatch(2);
        var start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Integer> adjust = () -> {
                ready.countDown(); start.await();
                return repository.adjustStock(product.getProductId(), 5, 0);
            };
            var first = executor.submit(adjust); var second = executor.submit(adjust);
            assertThat(ready.await(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
            start.countDown();
            assertThat(first.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    + second.get(5, java.util.concurrent.TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(mapper.selectById(product.getProductId()).getStock()).isEqualTo(105);
        assertThat(mapper.selectById(product.getProductId()).getVersion()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT expected_stock FROM catalog_product WHERE product_id=?", Long.class,
                product.getProductId())).isEqualTo(105L);
        assertThat(repository.adjustStock(product.getProductId(), -106, 1)).isZero();
        assertThat(mapper.reduceStock(product.getProductId(),1)).isEqualTo(1);
        assertThat(repository.adjustStock(product.getProductId(), 5, 1)).isZero();
        assertThat(mapper.increaseStock(product.getProductId(),1)).isEqualTo(1);
        assertThat(repository.adjustStock(product.getProductId(), 5, 2)).isZero();
        assertThat(mapper.selectById(product.getProductId()).getVersion()).isEqualTo(3L);
        assertThat(jdbc.queryForObject("SELECT expected_stock FROM catalog_product WHERE product_id=?", Long.class,
                product.getProductId())).isEqualTo(105L);
    }

    @Test void staleMetadataEditPreservesCommittedPurchase() {
        Product product = new Product(null,"original",new BigDecimal("10.00"),100,"test","description",null);
        mapper.insert(product);
        Product stale = mapper.selectById(product.getProductId());
        assertThat(mapper.reduceStock(product.getProductId(),1)).isEqualTo(1);
        stale.setName("renamed");
        assertThat(mapper.update(stale)).isEqualTo(1);
        assertThat(mapper.selectById(product.getProductId()).getStock()).isEqualTo(99);
    }
    @Test void staleMetadataEditPreservesCommittedRestoration() {
        Product product = new Product(null,"original",new BigDecimal("10.00"),99,"test","description",null);
        mapper.insert(product);
        Product stale = mapper.selectById(product.getProductId());
        assertThat(mapper.increaseStock(product.getProductId(),1)).isEqualTo(1);
        stale.setName("renamed");
        mapper.update(stale);
        assertThat(mapper.selectById(product.getProductId()).getStock()).isEqualTo(100);
    }
}
