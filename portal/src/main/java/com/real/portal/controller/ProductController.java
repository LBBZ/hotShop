package com.real.portal.controller;

import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.CursorPageResponse;
import com.real.common.api.dto.ProductResponse;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.Product;
import com.real.domain.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@Validated
@Tag(name = "Public products", description = "Anonymous Catalog Product discovery")
@RequestMapping("/api/v1/products")
public class ProductController {
    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "Get a Catalog Product")
    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable @Min(1) Long productId) {
        Product product = productService.getProductById(productId);
        if (product == null) {
            throw ApiException.notFound("Catalog Product");
        }
        return ResponseEntity.ok(ApiDtoMapper.toProductResponse(product));
    }

    @Operation(
            summary = "List Catalog Products",
            description = "Stable keyset pagination ordered by productId ascending"
    )
    @GetMapping
    public ResponseEntity<CursorPageResponse<ProductResponse>> getProducts(
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int limit,
            @RequestParam(required = false) String cursor,
            @RequestParam(required = false) @Size(max = 200) String keyword,
            @RequestParam(required = false) @Size(max = 100) String category,
            @RequestParam(required = false) @DecimalMin("0.00") BigDecimal minPrice,
            @RequestParam(required = false) @DecimalMin("0.00") BigDecimal maxPrice
    ) {
        if (minPrice != null && maxPrice != null && minPrice.compareTo(maxPrice) > 0) {
            throw ApiException.badRequest(
                    "PRICE_RANGE_INVALID",
                    "minPrice must be less than or equal to maxPrice"
            );
        }
        CursorSlice<Product> slice = productService.getProductsByCursor(
                limit,
                cursor,
                keyword,
                category,
                minPrice,
                maxPrice
        );
        return ResponseEntity.ok(new CursorPageResponse<>(
                slice.items().stream().map(ApiDtoMapper::toProductResponse).toList(),
                slice.nextCursor(),
                slice.hasMore()
        ));
    }
}
