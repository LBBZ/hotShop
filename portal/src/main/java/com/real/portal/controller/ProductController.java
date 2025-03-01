package com.real.portal.controller;

import com.github.pagehelper.PageInfo;
import com.real.domain.entity.Product;
import com.real.domain.mapper.StoredProcedure;
import com.real.domain.service.baseService.ProductService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/products")
public class ProductController {

    private final ProductService productService;
    private final StoredProcedure storedProcedure;
    @Autowired
    public ProductController(ProductService productService, StoredProcedure storedProcedure) {
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
        product.setId(id);
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

    @GetMapping("/page")
    public ResponseEntity<PageInfo<Product>> getProductPage(
            @RequestParam(defaultValue = "1") int pageNum,
            @RequestParam(defaultValue = "10") int pageSize,
            @RequestParam(required = false) String category) {
        return ResponseEntity.ok(productService.getProductByPage(pageNum, pageSize, category));
    }

    @GetMapping("/all")
    public ResponseEntity<List<Product>> getAllProducts() {
        return ResponseEntity.ok(productService.getAllProducts());
    }

    @GetMapping("/search")
    public ResponseEntity<List<Product>> searchProducts(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) Long minPrice,
            @RequestParam(required = false) Long maxPrice
    ) {
        List<Product> products = productService.getProductsByConditions(keyword, category, minPrice, maxPrice);
        return ResponseEntity.ok(products);
    }

}