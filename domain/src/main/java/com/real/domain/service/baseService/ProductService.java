package com.real.domain.service.baseService;

import com.github.pagehelper.Page;
import com.github.pagehelper.PageHelper;
import com.github.pagehelper.PageInfo;
import com.real.domain.entity.Product;
import com.real.domain.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
public class ProductService {
    private final ProductMapper productMapper;

    @Autowired
    public ProductService(ProductMapper productMapper) {
        this.productMapper = productMapper;
    }

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

        try (Page<Object> ignored = PageHelper.startPage(pageNum, pageSize)) {
            List<Product> products;
            if (category != null&& !category.isEmpty()) {
                products = productMapper.selectByCategory(category);
            } else {
                products = productMapper.selectAll();
            }
            return new PageInfo<>(products);
        }
    }
    public List<Product> getProductsByConditions(String keyword, String category, Long minPrice, Long maxPrice) {
        return productMapper.selectProductsByConditions(keyword, category, minPrice, maxPrice);
    }


}