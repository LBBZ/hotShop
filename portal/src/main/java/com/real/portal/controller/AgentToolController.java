package com.real.portal.controller;

import com.real.common.api.ApiException;
import com.real.common.api.dto.AgentOrderListResponse;
import com.real.common.api.dto.AgentProductComparisonRequest;
import com.real.common.api.dto.AgentProductComparisonResponse;
import com.real.common.api.dto.AgentProductSearchResponse;
import com.real.common.api.dto.AgentProductSummaryResponse;
import com.real.common.api.dto.AgentReservationListResponse;
import com.real.common.api.dto.PurchaseDraftCreateRequest;
import com.real.common.api.dto.PurchaseDraftResponse;
import com.real.domain.agenttools.AgentToolAuditWriter;
import com.real.domain.agenttools.AgentToolService;
import com.real.security.entity.CustomUserDetails;
import com.real.security.identity.IdentityType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

@RestController
@Validated
@RequestMapping("/agent/api/v1/tools")
public class AgentToolController {
    private final AgentToolService tools;
    private final AgentToolAuditWriter audit;

    public AgentToolController(AgentToolService tools, AgentToolAuditWriter audit) {
        this.tools = tools;
        this.audit = audit;
    }

    @GetMapping("/products")
    @PreAuthorize("hasAuthority('SCOPE_catalog:read')")
    public AgentProductSearchResponse searchProducts(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(required = false) @Size(max = 200) String keyword,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit,
            @RequestParam(required = false) @Pattern(regexp = "^[1-9][0-9]{0,18}$") String cursor,
            HttpServletRequest request
    ) {
        return audited(
                principal,
                "search_products",
                "CATALOG_PRODUCT",
                null,
                Map.of(
                        "keywordLength", keyword == null ? 0 : keyword.length(),
                        "limit", limit,
                        "cursorPresent", cursor != null
                ),
                request,
                () -> {
                    rejectUnexpectedQueryParameters(request, Set.of("keyword", "limit", "cursor"));
                    return tools.searchProducts(keyword, limit, cursor);
                }
        );
    }

    @GetMapping("/products/{productId}")
    @PreAuthorize("hasAuthority('SCOPE_catalog:read')")
    public AgentProductSummaryResponse getProduct(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Pattern(regexp = "^[1-9][0-9]{0,18}$") String productId,
            HttpServletRequest request
    ) {
        return audited(
                principal,
                "get_product",
                "CATALOG_PRODUCT",
                productId,
                Map.of("productId", productId),
                request,
                () -> {
                    rejectUnexpectedQueryParameters(request, Set.of());
                    return tools.getProduct(parseId(productId));
                }
        );
    }

    @PostMapping("/product-comparisons")
    @PreAuthorize("hasAuthority('SCOPE_catalog:read')")
    public AgentProductComparisonResponse compareProducts(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody @Valid AgentProductComparisonRequest body,
            HttpServletRequest request
    ) {
        List<Long> ids = body.productIds().stream().map(AgentToolController::parseId).toList();
        return audited(
                principal,
                "compare_products",
                "CATALOG_PRODUCT",
                null,
                Map.of(
                        "productCount", ids.size(),
                        "productSetDigest", com.real.domain.agenttools.PurchaseParameters.sha256(
                                body.productIds().stream().sorted().reduce(
                                        (left, right) -> left + "," + right
                                ).orElse("")
                        )
                ),
                request,
                () -> {
                    rejectUnexpectedQueryParameters(request, Set.of());
                    return tools.compareProducts(ids);
                }
        );
    }

    @GetMapping("/orders")
    @PreAuthorize("hasAuthority('SCOPE_orders:self:read')")
    public AgentOrderListResponse listOrders(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit,
            @RequestParam(required = false) @Size(max = 512) String cursor,
            HttpServletRequest request
    ) {
        return audited(
                principal,
                "list_my_orders",
                "SALES_ORDER",
                null,
                Map.of("limit", limit, "cursorPresent", cursor != null),
                request,
                () -> {
                    rejectUnexpectedQueryParameters(request, Set.of("limit", "cursor"));
                    return tools.listOrders(principal.getUserId(), limit, cursor);
                }
        );
    }

    @GetMapping("/reservations")
    @PreAuthorize("hasAuthority('SCOPE_reservations:self:read')")
    public AgentReservationListResponse listReservations(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit,
            HttpServletRequest request
    ) {
        return audited(
                principal,
                "list_my_reservations",
                "SALE_RESERVATION",
                null,
                Map.of("limit", limit),
                request,
                () -> {
                    rejectUnexpectedQueryParameters(request, Set.of("limit"));
                    return tools.listReservations(principal.getUserId(), limit);
                }
        );
    }

    @PostMapping("/purchase-drafts")
    @PreAuthorize("hasAuthority('SCOPE_purchase-drafts:create')")
    public PurchaseDraftResponse createPurchaseDraft(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody @Valid PurchaseDraftCreateRequest body,
            HttpServletRequest request
    ) {
        validatePrincipal(principal);
        try {
            rejectUnexpectedQueryParameters(request, Set.of());
            return tools.createDraft(
                    principal.getUserId(), principal.getAuthorizedParty(), body, request
            );
        } catch (RuntimeException exception) {
            audit.appendAgentToolFailure(
                    principal.getAuthorizedParty(), principal.getUserId(),
                    "create_purchase_draft", "PURCHASE_DRAFT", null, "FAILURE",
                    Map.of("itemCount", body.items().size()), request
            );
            throw exception;
        }
    }

    private <T> T audited(
            CustomUserDetails principal,
            String tool,
            String resourceType,
            String resourceId,
            Map<String, Object> summary,
            HttpServletRequest request,
            Supplier<T> operation
    ) {
        validatePrincipal(principal);
        try {
            T result = operation.get();
            audit.appendAgentTool(
                    principal.getAuthorizedParty(), principal.getUserId(), tool,
                    resourceType, resourceId, "SUCCESS", summary, request
            );
            return result;
        } catch (RuntimeException exception) {
            audit.appendAgentToolFailure(
                    principal.getAuthorizedParty(), principal.getUserId(), tool,
                    resourceType, resourceId, "FAILURE", summary, request
            );
            throw exception;
        }
    }

    private static void validatePrincipal(CustomUserDetails principal) {
        if (principal == null
                || principal.getIdentityType() != IdentityType.AGENT_DELEGATION
                || principal.getUserId() == null
                || principal.getUserId() <= 0
                || principal.getAuthorizedParty() == null
                || principal.getAuthorizedParty().isBlank()) {
            throw ApiException.forbidden(
                    "AGENT_DELEGATION_REQUIRED",
                    "A valid Agent Delegation is required"
            );
        }
    }

    private static long parseId(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new NumberFormatException();
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest("PRODUCT_ID_INVALID", "Product ID is invalid");
        }
    }

    private static void rejectUnexpectedQueryParameters(
            HttpServletRequest request,
            Set<String> allowed
    ) {
        if (!allowed.containsAll(request.getParameterMap().keySet())) {
            throw ApiException.badRequest(
                    "AGENT_TOOL_SCHEMA_INVALID",
                    "The tool request contains an unexpected query parameter"
            );
        }
    }
}
