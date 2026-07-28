package com.real.domain.service;

import com.github.pagehelper.PageInfo;
import com.real.common.api.CursorCodec;
import com.real.common.api.CursorSlice;
import com.real.common.util.PageHelperUtils;
import com.real.domain.entity.Product;
import com.real.domain.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
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
    public PageInfo<Product> getProductsByConditions(int pageNum, int pageSize, String keyword, String category, BigDecimal minPrice, BigDecimal maxPrice) {
        List<Product> products = productMapper.selectProductsByConditions(keyword, category, minPrice, maxPrice);
        return pageHelperUtils.getPageInfo(pageNum, pageSize, products);
    }

    public CursorSlice<Product> getProductsByCursor(
            int limit,
            String cursor,
            String keyword,
            String category,
            BigDecimal minPrice,
            BigDecimal maxPrice
    ) {
        CursorCodec.LongCursor decoded = CursorCodec.decodeLong(cursor, "products");
        List<Product> fetched = productMapper.selectProductsByCursor(
                keyword,
                category,
                minPrice,
                maxPrice,
                decoded == null ? null : decoded.id(),
                limit + 1
        );
        boolean hasMore = fetched.size() > limit;
        List<Product> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasMore
                ? CursorCodec.encodeLong("products", items.get(items.size() - 1).getProductId())
                : null;
        return new CursorSlice<>(items, nextCursor, hasMore);
    }

}
