package com.real.admin.controller;

import com.github.pagehelper.PageInfo;
import com.real.domain.entity.Product;
import com.real.domain.mapper.StoredProcedure;
import com.real.domain.service.baseService.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "管理端-商品管理", description = "商品信息管理（需管理员权限）")
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

    @Operation(
        summary = "添加商品",
        description = "创建新商品信息",
        security = @SecurityRequirement(name = "JWT"),
        responses = {
            @ApiResponse(responseCode = "200", description = "添加成功",
                content = @Content(schema = @Schema(example = "商品添加成功"))),
                @ApiResponse(responseCode = "400", description = "参数验证失败")
            }
    )
    @PostMapping("/add")
    public ResponseEntity<String> addProduct(@RequestBody Product product) {
        productService.addProduct(product);
        return ResponseEntity.ok("商品添加成功");
    }

    @Operation(
        summary = "更新商品",
        description = "根据ID更新商品信息（需传递完整对象）",
        security = @SecurityRequirement(name = "JWT"),
        responses = {
            @ApiResponse(responseCode = "200", description = "更新成功",
            content = @Content(schema = @Schema(example = "商品更新成功"))),
                @ApiResponse(responseCode = "404", description = "商品不存在")
        }
    )
    @PutMapping("/{id}")
    public ResponseEntity<String> updateProduct(
            @PathVariable Long id,
            @RequestBody Product product
    ) {
        product.setProductId(id);
        productService.updateProduct(product);
        return ResponseEntity.ok("商品更新成功");
    }

    @Operation(
            summary = "删除商品",
            description = "根据ID删除商品（自动重置自增主键）",
            security = @SecurityRequirement(name = "JWT"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "删除成功",
                            content = @Content(schema = @Schema(example = "商品删除成功"))),
                            @ApiResponse(responseCode = "404", description = "商品不存在")
                            }
                    )
    @DeleteMapping("/{id}")
    public ResponseEntity<String> deleteProduct(@PathVariable Long id) {
        productService.deleteProduct(id);
        storedProcedure.resetAutoIncrement("product");
        return ResponseEntity.ok("商品删除成功");
    }

    @Operation(
            summary = "查看商品详情",
            description = "根据ID获取商品完整信息",
            security = @SecurityRequirement(name = "JWT"),
            responses = {
                    @ApiResponse(responseCode = "200", description = "查询成功",
                            content = @Content(schema = @Schema(implementation = Product.class))),
                    @ApiResponse(responseCode = "404", description = "商品不存在")
            }
    )
    @GetMapping("/{id}")
    public ResponseEntity<Product> getProduct(@PathVariable Long id) {
        Product product = productService.getProductById(id);
        return ResponseEntity.ok(product);
    }

    @Operation(
            summary = "高级商品搜索",
            description = "多条件分页查询商品信息",
            security = @SecurityRequirement(name = "JWT"),
            parameters = {
                    @Parameter(name = "keyword", description = "商品名称/描述关键词", example = "手机"),
                    @Parameter(name = "category", description = "商品分类", example = "电子产品"),
                    @Parameter(name = "minPrice", description = "最低价格（单位：分）", example = "1000"),
                    @Parameter(name = "maxPrice", description = "最高价格（单位：分）", example = "10000")
            },
            responses = @ApiResponse(
                    content = @Content(schema = @Schema(implementation = PageInfo.class, example = """
                {
                  "pageNum": 1,
                  "pageSize": 10,
                  "total": 100,
                  "list": [
                    {
                      "productId": 456,
                      "name": "iPhone 15",
                      "price": 6999.00,
                      "stock": 100,
                      "category": "电子产品"
                    }
                  ]
                }"""))
            )
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