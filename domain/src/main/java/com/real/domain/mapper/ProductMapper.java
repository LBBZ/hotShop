package com.real.domain.mapper;

import com.real.domain.entity.Product;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProductMapper {
    int insert(Product product);
    int update(Product product);
    int reduceStock(Long productId, Integer quantity);
    int delete(Long id);
    Product selectById(Long id);
    List<Product> selectAll();
    List<Product> selectByCategory(String category);
}