package com.real.domain.adminops;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class AdminProductMutationRepository {
    private final JdbcTemplate jdbc;

    public AdminProductMutationRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Compare the version read by the administrator; a signed delta is never an absolute overwrite. */
    public int adjustStock(long productId, int delta, long expectedVersion) {
        return jdbc.update("""
                UPDATE catalog_product
                   SET stock=stock+?, expected_stock=expected_stock+?, version=version+1
                 WHERE product_id=? AND deleted_at IS NULL AND version=?
                   AND CAST(stock AS SIGNED)+? BETWEEN 0 AND 2147483647
                """, delta, delta, productId, expectedVersion, delta);
    }

    public boolean lockActiveProduct(long productId) {
        return !jdbc.query(
                "SELECT product_id FROM catalog_product WHERE product_id=? AND deleted_at IS NULL FOR UPDATE",
                (resultSet, rowNumber) -> resultSet.getLong(1),
                productId
        ).isEmpty();
    }
}
