package com.real.admin.controller;

import com.github.pagehelper.PageInfo;
import com.real.domain.entity.baseEntity.Product;
import com.real.domain.mapper.StoredProcedure;
import com.real.domain.service.baseService.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/admin/products")
@PreAuthorize("hasRole('ROLE_ADMIN')")
public class AdminProductController {
    private final ProductService productService;
    private final StoredProcedure storedProcedure;
    @Autowired
    public AdminProductController(ProductService productService, StoredProcedure storedProcedure) {
        this.productService = productService;
        this.storedProcedure = storedProcedure;
    }

    @PostMapping("/add")
    public ResponseEntity<String> addProduct(@RequestBody Product product) {
        productService.addProduct(product);
        return ResponseEntity.ok("商品添加成功");
    }

    @PutMapping("/{id}")
    public ResponseEntity<String> updateProduct(
            @PathVariable Long id,
            @RequestBody Product product
    ) {
        product.setProductId(id);
        productService.updateProduct(product);
        return ResponseEntity.ok("商品更新成功");
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        storedProcedure.resetAutoIncrement("product");
        return ResponseEntity.ok("商品删除成功");
    }

    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        Product product = productService.getProductById(id);
        return ResponseEntity.ok(product);
    }

    @GetMapping("/all")
    public ResponseEntity<List<Product>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    @GetMapping("/page")
    public ResponseEntity<PageInfo<Product>> getProductPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(productService.getProductByPage(pageNum, pageSize, category));
    }

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