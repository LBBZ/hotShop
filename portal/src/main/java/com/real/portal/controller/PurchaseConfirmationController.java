package com.real.portal.controller;

import com.real.common.api.RequestContext;
import com.real.common.api.dto.PurchaseConfirmationConsumeRequest;
import com.real.common.api.dto.PurchaseConfirmationIssueRequest;
import com.real.common.api.dto.PurchaseConfirmationIssueResponse;
import com.real.common.api.dto.PurchaseConfirmationOrderResponse;
import com.real.domain.agenttools.AgentToolAuditWriter;
import com.real.domain.agenttools.PurchaseConfirmationService;
import com.real.security.entity.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@Validated
@RequestMapping("/api/v1/orders")
@PreAuthorize("hasAuthority('ROLE_USER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(
        name = "User purchase confirmation",
        description = "Explicit one-time confirmation for Agent-created Purchase Drafts"
)
public class PurchaseConfirmationController {
    private static final String UUID_V4 =
            "^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$";

    private final PurchaseConfirmationService confirmations;
    private final AgentToolAuditWriter audit;

    public PurchaseConfirmationController(
            PurchaseConfirmationService confirmations,
            AgentToolAuditWriter audit
    ) {
        this.confirmations = confirmations;
        this.audit = audit;
    }

    @PostMapping("/purchase-drafts/{draftId}/confirmations")
    @Operation(summary = "Issue a one-time confirmation for an owned Purchase Draft")
    public PurchaseConfirmationIssueResponse issue(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Pattern(regexp = UUID_V4) String draftId,
            @RequestBody @Valid PurchaseConfirmationIssueRequest body,
            HttpServletRequest request
    ) {
        try {
            rejectQueryParameters(request);
            return confirmations.issue(principal.getUserId(), draftId, body.actionType(), request);
        } catch (RuntimeException exception) {
            audit.appendConfirmationFailure(
                    principal.getUserId(), "PURCHASE_CONFIRMATION_ISSUE_DENIED",
                    "PURCHASE_DRAFT", draftId,
                    Map.of("schemaVersion", 1, "actionType", body.actionType()), request
            );
            throw exception;
        }
    }

    @PostMapping("/purchase-confirmations/consume")
    @Operation(summary = "Atomically consume a one-time confirmation and create an Order")
    public PurchaseConfirmationOrderResponse consume(
            @AuthenticationPrincipal CustomUserDetails principal,
            @RequestBody @Valid PurchaseConfirmationConsumeRequest body,
            HttpServletRequest request
    ) {
        try {
            rejectQueryParameters(request);
            return confirmations.consume(
                    principal.getUserId(), body, RequestContext.requestId(request), request
            );
        } catch (RuntimeException exception) {
            audit.appendConfirmationFailure(
                    principal.getUserId(), "PURCHASE_CONFIRMATION_CONSUME_DENIED",
                    "PURCHASE_DRAFT", body.draftId(),
                    Map.of(
                            "schemaVersion", 1,
                            "actionType", body.actionType(),
                            "itemCount", body.items().size()
                    ),
                    request
            );
            throw exception;
        }
    }

    @DeleteMapping("/purchase-drafts/{draftId}/confirmation")
    @Operation(summary = "Revoke an unconsumed Purchase confirmation")
    public ResponseEntity<Void> revoke(
            @AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Pattern(regexp = UUID_V4) String draftId,
            HttpServletRequest request
    ) {
        try {
            rejectQueryParameters(request);
            confirmations.revoke(principal.getUserId(), draftId, request);
            return ResponseEntity.noContent().build();
        } catch (RuntimeException exception) {
            audit.appendConfirmationFailure(
                    principal.getUserId(), "PURCHASE_CONFIRMATION_REVOKE_DENIED",
                    "PURCHASE_DRAFT", draftId,
                    Map.of("schemaVersion", 1), request
            );
            throw exception;
        }
    }

    private static void rejectQueryParameters(HttpServletRequest request) {
        if (!request.getParameterMap().isEmpty()) {
            throw com.real.common.api.ApiException.badRequest(
                    "PURCHASE_CONFIRMATION_SCHEMA_INVALID",
                    "Query parameters are not allowed"
            );
        }
    }
}
