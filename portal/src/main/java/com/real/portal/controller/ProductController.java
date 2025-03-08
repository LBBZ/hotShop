package com.real.portal.controller;

import com.github.pagehelper.PageInfo;
import com.real.domain.entity.baseEntity.Product;
import com.real.domain.service.baseService.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@Tag(name = "商品接口", description = "商品信息查询（公开接口）")
@RequestMapping("/portal/products")
public class ProductController {
    private final ProductService productService;
    @Autowired
    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "获取商品详情", description = "根据ID查询商品详细信息")
    @GetMapping("/{productId}")
    public ResponseEntity<Product> getProduct(@PathVariable Long productId) {
        Product product = productService.getProductById(productId);
        return ResponseEntity.ok(product);
    }

    @Operation(
        summary = "分页查询商品",
        description = "按分类筛选商品列表",
        parameters = {
            @Parameter(name = "pageNum", description = "页码", example = "1"),
            @Parameter(name = "pageSize", description = "每页数量", example = "10"),
            @Parameter(name = "category", description = "商品分类", example = "电子产品")
        }
    )
    @GetMapping("/page")
    public ResponseEntity<PageInfo<Product>> getProductPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(productService.getProductByPage(pageNum, pageSize, category));
    }

    @Operation(summary = "获取所有商品", description = "获取全部商品列表（测试用）")
    @GetMapping("/all")
    public ResponseEntity<List<Product>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    @Operation(
        summary = "多条件搜索商品",
        description = "根据关键词、分类、价格区间筛选商品",
        parameters = {
            @Parameter(name = "keyword", description = "搜索关键词", example = "手机"),
            @Parameter(name = "minPrice", description = "最低价格（分）", example = "1000"),
            @Parameter(name = "maxPrice", description = "最高价格（分）", example = "10000")
        }
    )
    @GetMapping("/search")
    public ResponseEntity<PageInfo<Product>> searchProducts(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice
    ) {
        PageInfo<Product> products = productService.getProductsByConditions(pageNum, pageSize, keyword, category, minPrice, maxPrice);
        return ResponseEntity.ok(products);
    }

}