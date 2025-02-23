package com.xh.mall.service;

import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.xh.mall.entity.Product;
import com.xh.mall.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {
    @Autowired
    private ProductMapper productMapper;

    public void addProduct(Product product) {
        productMapper.insert(product);
    }

    public void updateProduct(Product product) {
        productMapper.update(product);
    }

    public void deleteProduct(Long id) {
        productMapper.delete(id);
    }

    public Product getProductById(Long id) {
        return productMapper.selectById(id);
    }

    public List<Product> getAllProducts() {
        return productMapper.selectAll();
    }

    public PageInfo<Product> getProductByPage(int pageNum, int pageSize, String category) {
        PageHelper.startPage(pageNum, pageSize);
        List<Product> products;
        if (category != null&& !category.isEmpty()) {
            products = productMapper.selectByCategory(category);
        } else {
            products = productMapper.selectAll();
        }
        return new PageInfo<>(products);
    }
}