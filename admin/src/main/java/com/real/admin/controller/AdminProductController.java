package com.real.admin.controller;

import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.CursorPageResponse;
import com.real.common.api.dto.ProductResponse;
import com.real.common.api.dto.ProductWriteRequest;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.Product;
import com.real.domain.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;

@RestController
@Validated
@Tag(name = "Admin products", description = "Administrator Catalog Product operations")
@RequestMapping("/admin/api/v1/products")
@PreAuthorize("hasAuthority('ROLE_ADMIN')")
@SecurityRequirement(name = "bearerAuth")
public class AdminProductController {
    private final ProductService productService;

    public AdminProductController(ProductService productService) {
        this.productService = productService;
    }

    @Operation(summary = "Create a Catalog Product")
    @PostMapping
    public ResponseEntity<ProductResponse> addProduct(
            @RequestBody @Valid ProductWriteRequest request
    ) {
        Product product = ApiDtoMapper.toProduct(request);
        productService.addProduct(product);
        Product created = productService.getProductById(product.getProductId());
        if (created == null) {
            throw new IllegalStateException("Created product could not be loaded");
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiDtoMapper.toProductResponse(created));
    }

    @Operation(summary = "Replace a Catalog Product")
    @PutMapping("/{productId}")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable @Min(1) Long productId,
            @RequestBody @Valid ProductWriteRequest request
    ) {
        requireProduct(productId);
        Product product = ApiDtoMapper.toProduct(request);
        product.setProductId(productId);
        productService.updateProduct(product);
        return ResponseEntity.ok(ApiDtoMapper.toProductResponse(requireProduct(productId)));
    }

    @Operation(summary = "Soft-delete a Catalog Product")
    @DeleteMapping("/{productId}")
    public ResponseEntity<Void> deleteProduct(@PathVariable @Min(1) Long productId) {
        requireProduct(productId);
        productService.deleteProduct(productId);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get a Catalog Product")
    @GetMapping("/{productId}")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable @Min(1) Long productId) {
        return ResponseEntity.ok(ApiDtoMapper.toProductResponse(requireProduct(productId)));
    }

    @Operation(
            summary = "List Catalog Products",
            description = "Stable keyset pagination ordered by productId ascending"
    )
    @GetMapping
    public ResponseEntity<CursorPageResponse<ProductResponse>> searchProducts(
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

    private Product requireProduct(Long productId) {
        Product product = productService.getProductById(productId);
        if (product == null) {
            throw ApiException.notFound("Catalog Product");
        }
        return product;
    }
}
