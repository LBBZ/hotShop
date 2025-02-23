package com.xh.mall.mapper;

import com.xh.mall.entity.Product;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

@Mapper
public interface ProductMapper {
    void insert(Product product);
    void update(Product product);
    void delete(Long id);
    Product selectById(Long id);
    List<Product> selectAll();
    List<Product> selectByCategory(String category);
}