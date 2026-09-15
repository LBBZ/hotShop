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
        Flyway.configure().dataSource(source).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(source);
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
