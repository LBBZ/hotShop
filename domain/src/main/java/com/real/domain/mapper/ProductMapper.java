package com.real.domain.mapper;

import com.real.domain.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.util.List;

@Mapper
public interface ProductMapper {
    int insert(Product product);
    int delete(Long productId);
    int update(Product product);
    int reduceStock(Long productId, Integer quantity);
    int increaseStock(Long productId, Integer quantity);
    Product selectById(Long productId);
    List<Product> selectAll();

    List<Product> selectByCategory(String category);
    List<Product> selectProductsByConditions(
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice
    );

    List<Product> selectProductsByCursor(
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("minPrice") BigDecimal minPrice,
            @Param("maxPrice") BigDecimal maxPrice,
            @Param("afterProductId") Long afterProductId,
            @Param("limit") int limit
    );
}
