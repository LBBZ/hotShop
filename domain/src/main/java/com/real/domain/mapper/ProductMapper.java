package com.real.domain.mapper;

import com.real.common.enums.OrderStatus;
import com.real.domain.entity.Order;
import com.real.domain.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface ProductMapper {
    int insert(Product product);
    int delete(Long id);
    int update(Product product);
    int reduceStock(Long productId, Integer quantity);
    int increaseStock(Long productId, Integer quantity);
    Product selectById(Long id);
    List<Product> selectAll();

    List<Product> selectByCategory(String category);
    List<Product> selectProductsByConditions(
            @Param("keyword") String keyword,
            @Param("category") String category,
            @Param("minPrice") Long minPrice,
            @Param("maxPrice") Long maxPrice
    );
}