package com.real.domain.service.baseService;

import com.github.pagehelper.PageInfo;
import com.real.common.util.PageHelperUtils;
import com.real.domain.entity.Product;
import com.real.domain.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ProductService {
    private final PageHelperUtils<Product> pageHelperUtils;
    private final ProductMapper productMapper;
    @Autowired
    public ProductService(PageHelperUtils<Product> pageHelperUtils, ProductMapper productMapper) {
        this.pageHelperUtils = pageHelperUtils;
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
        List<Product> products;
        if (category != null&& !category.isEmpty()) {
            products = productMapper.selectByCategory(category);
        } else {
            products = productMapper.selectAll();
        }
            return pageHelperUtils.getPageInfo(pageNum, pageSize, products);
    }
    public PageInfo<Product> getProductsByConditions(int pageNum, int pageSize, String keyword, String category, Long minPrice, Long maxPrice) {
        List<Product> products = productMapper.selectProductsByConditions(keyword, category, minPrice, maxPrice);
        return pageHelperUtils.getPageInfo(pageNum, pageSize, products);
    }


}