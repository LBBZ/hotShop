package com.real.admin.service;

import com.real.common.api.ApiException;
import com.real.common.api.RequestContext;
import com.real.common.audit.AuditAction;
import com.real.common.audit.AuditActor;
import com.real.common.audit.AuditActorType;
import com.real.common.audit.AuditEvent;
import com.real.common.audit.AuditResource;
import com.real.common.audit.AuditResourceType;
import com.real.common.audit.AuditResult;
import com.real.common.audit.AuditSource;
import com.real.common.audit.OperationFailureAuditState;
import com.real.common.audit.ProductMutationAuditState;
import com.real.domain.entity.Product;
import com.real.domain.service.ProductService;
import com.real.security.audit.AuditLogWriter;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
public class AdminProductAuditService {
    private static final List<String> PRODUCT_FIELDS =
            List.of("name", "price", "stock", "category", "description");

    private final ProductService productService;
    private final AuditLogWriter auditLogWriter;

    public AdminProductAuditService(ProductService productService, AuditLogWriter auditLogWriter) {
        this.productService = productService;
        this.auditLogWriter = auditLogWriter;
    }

    @Transactional
    public Product create(Product product, long administratorId, HttpServletRequest request) {
        try {
            productService.addProduct(product);
            Product created = productService.getProductById(product.getProductId());
            if (created == null) {
                throw new IllegalStateException("Created product could not be loaded");
            }
            auditLogWriter.append(event(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_CREATED,
                    product.getProductId(),
                    AuditResult.SUCCESS,
                    new ProductMutationAuditState(PRODUCT_FIELDS, "ACTIVE"),
                    request
            ));
            return created;
        } catch (RuntimeException exception) {
            appendFailure(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_CREATED,
                    product.getProductId(),
                    exception,
                    request
            );
            throw exception;
        }
    }

    @Transactional
    public Product update(
            long productId,
            Product product,
            long administratorId,
            HttpServletRequest request
    ) {
        try {
            requireProduct(productId);
            product.setProductId(productId);
            productService.updateProduct(product);
            Product updated = requireProduct(productId);
            auditLogWriter.append(event(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_UPDATED,
                    productId,
                    AuditResult.SUCCESS,
                    new ProductMutationAuditState(PRODUCT_FIELDS, "ACTIVE"),
                    request
            ));
            return updated;
        } catch (RuntimeException exception) {
            appendFailure(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_UPDATED,
                    productId,
                    exception,
                    request
            );
            throw exception;
        }
    }

    @Transactional
    public void delete(long productId, long administratorId, HttpServletRequest request) {
        try {
            requireProduct(productId);
            productService.deleteProduct(productId);
            auditLogWriter.append(event(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_DELETED,
                    productId,
                    AuditResult.SUCCESS,
                    new ProductMutationAuditState(List.of("status", "deletedAt"), "INACTIVE"),
                    request
            ));
        } catch (RuntimeException exception) {
            appendFailure(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_DELETED,
                    productId,
                    exception,
                    request
            );
            throw exception;
        }
    }

    private Product requireProduct(long productId) {
        Product product = productService.getProductById(productId);
        if (product == null) {
            throw ApiException.notFound("Catalog Product");
        }
        return product;
    }

    private void appendFailure(
            long administratorId,
            AuditAction action,
            Long productId,
            RuntimeException exception,
            HttpServletRequest request
    ) {
        auditLogWriter.appendFailure(event(
                administratorId,
                action,
                productId,
                AuditResult.FAILURE,
                new OperationFailureAuditState(reasonCode(exception)),
                request
        ));
    }

    private AuditEvent event(
            long administratorId,
            AuditAction action,
            Long productId,
            AuditResult result,
            com.real.common.audit.AuditStateSummary state,
            HttpServletRequest request
    ) {
        return new AuditEvent(
                AuditActor.identified(AuditActorType.ADMIN, administratorId),
                null,
                action,
                new AuditResource(
                        AuditResourceType.CATALOG_PRODUCT,
                        productId == null ? null : productId.toString()
                ),
                result,
                RequestContext.requestId(request),
                RequestContext.traceId(request),
                AuditSource.ADMIN_API,
                Instant.now(),
                state
        );
    }

    private String reasonCode(RuntimeException exception) {
        if (exception instanceof ApiException apiException) {
            return apiException.getCode();
        }
        return "OPERATION_FAILED";
    }
}
