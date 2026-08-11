package com.real.admin.controller;

import com.real.admin.service.AdminProductAuditService;
import com.real.admin.service.AdminOperationsService;
import com.real.common.api.ApiException;
import com.real.common.api.CursorSlice;
import com.real.common.api.dto.CursorPageResponse;
import com.real.common.api.dto.ProductResponse;
import com.real.common.api.dto.AdminProductMutationRequest;
import com.real.common.api.dto.AdminOperationReasonRequest;
import com.real.domain.api.ApiDtoMapper;
import com.real.domain.entity.Product;
import com.real.domain.service.ProductService;
import com.real.security.entity.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
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
@SecurityRequirement(name = "bearerAuth")
public class AdminProductController {
    private final ProductService productService;
    private final AdminProductAuditService productAuditService;
    private final AdminOperationsService operationsService;

    public AdminProductController(
            ProductService productService,
            AdminProductAuditService productAuditService,
            AdminOperationsService operationsService
    ) {
        this.productService = productService;
        this.productAuditService = productAuditService;
        this.operationsService = operationsService;
    }

    @Operation(summary = "Create a Catalog Product")
    @PostMapping
    @PreAuthorize("hasAuthority('PERM_ADMIN_PRODUCT_WRITE')")
    public ResponseEntity<ProductResponse> addProduct(
            @RequestBody @Valid AdminProductMutationRequest request,
            @AuthenticationPrincipal CustomUserDetails administrator,
            HttpServletRequest servletRequest
    ) {
        Product product = ApiDtoMapper.toProduct(request.product());
        Product created = productAuditService.create(
                product,
                administrator.getUserId(),
                request.reason(), servletRequest
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiDtoMapper.toProductResponse(created));
    }

    @Operation(summary = "Replace a Catalog Product")
    @PutMapping("/{productId}")
    @PreAuthorize("hasAuthority('PERM_ADMIN_PRODUCT_WRITE')")
    public ResponseEntity<ProductResponse> updateProduct(
            @PathVariable @Min(1) Long productId,
            @RequestBody @Valid AdminProductMutationRequest request,
            @AuthenticationPrincipal CustomUserDetails administrator,
            HttpServletRequest servletRequest
    ) {
        Product product = ApiDtoMapper.toProduct(request.product());
        Product updated = productAuditService.update(
                productId,
                product,
                administrator.getUserId(),
                request.reason(), servletRequest
        );
        return ResponseEntity.ok(ApiDtoMapper.toProductResponse(updated));
    }

    @Operation(summary = "Soft-delete a Catalog Product")
    @DeleteMapping("/{productId}")
    @PreAuthorize("hasAuthority('PERM_ADMIN_PRODUCT_WRITE')")
    public ResponseEntity<Void> deleteProduct(
            @PathVariable @Min(1) Long productId,
            @RequestBody @Valid AdminOperationReasonRequest request,
            @AuthenticationPrincipal CustomUserDetails administrator,
            HttpServletRequest servletRequest
    ) {
        productAuditService.delete(productId, administrator.getUserId(), request.reason(), servletRequest);
        return ResponseEntity.noContent().build();
    }

    @Operation(summary = "Get a Catalog Product")
    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority('PERM_ADMIN_PRODUCT_READ')")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable @Min(1) Long productId) {
        return ResponseEntity.ok(ApiDtoMapper.toProductResponse(requireProduct(productId)));
    }

    @Operation(
            summary = "List Catalog Products",
            description = "Stable keyset pagination ordered by productId ascending"
    )
    @GetMapping
    @PreAuthorize("hasAuthority('PERM_ADMIN_PRODUCT_READ')")
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
        CursorSlice<ProductResponse> slice = operationsService.products(
                limit,
                cursor,
                keyword,
                category,
                minPrice,
                maxPrice
        );
        return ResponseEntity.ok(new CursorPageResponse<>(slice.items(), slice.nextCursor(), slice.hasMore()));
    }

    private Product requireProduct(Long productId) {
        Product product = productService.getProductById(productId);
        if (product == null) {
            throw ApiException.notFound("Catalog Product");
        }
        return product;
    }
}
