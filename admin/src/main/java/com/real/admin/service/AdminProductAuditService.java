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
import com.real.common.audit.AdminOperationFailureAuditState;
import com.real.common.audit.AdminProductMutationAuditState;
import com.real.domain.adminops.AdminProductMutationRepository;
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
    private final AdminProductMutationRepository mutationRepository;
    private final AuditLogWriter auditLogWriter;

    public AdminProductAuditService(
            ProductService productService,
            AdminProductMutationRepository mutationRepository,
            AuditLogWriter auditLogWriter
    ) {
        this.productService = productService;
        this.mutationRepository = mutationRepository;
        this.auditLogWriter = auditLogWriter;
    }

    @Transactional
    public Product create(Product product, long administratorId, String reason, HttpServletRequest request) {
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
                    new AdminProductMutationAuditState(PRODUCT_FIELDS, "ACTIVE", reason),
                    request
            ));
            return created;
        } catch (RuntimeException exception) {
            appendFailure(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_CREATED,
                    product.getProductId(),
                    reason,
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
            String reason,
            HttpServletRequest request
    ) {
        try {
            lockProduct(productId);
            product.setProductId(productId);
            productService.updateProduct(product);
            Product updated = requireProduct(productId);
            auditLogWriter.append(event(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_UPDATED,
                    productId,
                    AuditResult.SUCCESS,
                    new AdminProductMutationAuditState(PRODUCT_FIELDS, "ACTIVE", reason),
                    request
            ));
            return updated;
        } catch (RuntimeException exception) {
            appendFailure(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_UPDATED,
                    productId,
                    reason,
                    exception,
                    request
            );
            throw exception;
        }
    }

    @Transactional
    public void delete(long productId, long administratorId, String reason, HttpServletRequest request) {
        try {
            lockProduct(productId);
            productService.deleteProduct(productId);
            auditLogWriter.append(event(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_DELETED,
                    productId,
                    AuditResult.SUCCESS,
                    new AdminProductMutationAuditState(List.of("status", "deletedAt"), "INACTIVE", reason),
                    request
            ));
        } catch (RuntimeException exception) {
            appendFailure(
                    administratorId,
                    AuditAction.CATALOG_PRODUCT_DELETED,
                    productId,
                    reason,
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

    private void lockProduct(long productId) {
        if (!mutationRepository.lockActiveProduct(productId)) {
            throw ApiException.notFound("Catalog Product");
        }
    }

    private void appendFailure(
            long administratorId,
            AuditAction action,
            Long productId,
            String administratorReason,
            RuntimeException exception,
            HttpServletRequest request
    ) {
        auditLogWriter.appendFailure(event(
                administratorId,
                action,
                productId,
                AuditResult.FAILURE,
                new AdminOperationFailureAuditState(reasonCode(exception), administratorReason),
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
