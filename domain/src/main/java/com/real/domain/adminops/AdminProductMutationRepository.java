package com.real.domain.adminops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminProductMutationRepository {
    private final JdbcTemplate jdbc;

    public AdminProductMutationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean lockActiveProduct(long productId) {
        return !jdbc.query(
                "SELECT product_id FROM catalog_product WHERE product_id=? AND deleted_at IS NULL FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getLong(1),
                productId
        ).isEmpty();
    }
}
