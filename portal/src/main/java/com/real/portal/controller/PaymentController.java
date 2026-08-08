package com.real.portal.controller;

import com.real.common.api.dto.MockPaymentActionRequest;
import com.real.common.api.dto.MockPaymentActionResponse;
import com.real.common.api.dto.PaymentResponse;
import com.real.portal.payment.PaymentService;
import com.real.security.entity.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@PreAuthorize("hasAuthority('ROLE_USER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "User Mock Payments", description = "Local demonstration only; no real funds are transferred")
public class PaymentController {
    private final PaymentService service;
    public PaymentController(PaymentService service) { this.service = service; }

    @Operation(summary = "Create or return the Order's unique local Mock Payment")
    @PostMapping("/api/v1/orders/{orderId}/payments")
    public ResponseEntity<PaymentResponse> create(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,50}") String orderId) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(principal.getUserId(), orderId));
    }

    @Operation(summary = "Get the current User's local Mock Payment")
    @GetMapping("/api/v1/payments/{paymentNo}")
    public PaymentResponse get(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Pattern(regexp = "MOCK_[0-9a-f]{32}") String paymentNo) {
        return service.get(principal.getUserId(), paymentNo);
    }

    @Operation(summary = "Schedule a local Mock checkout action", description = "Returns 202. Local demo only; this is not a real payment.")
    @ApiResponse(responseCode = "202", description = "Mock callback delivery accepted",
            content = @Content(schema = @Schema(implementation = MockPaymentActionResponse.class)))
    @PostMapping("/api/v1/payments/{paymentNo}/mock-actions")
    public ResponseEntity<MockPaymentActionResponse> action(@AuthenticationPrincipal CustomUserDetails principal,
            @PathVariable @Pattern(regexp = "MOCK_[0-9a-f]{32}") String paymentNo,
            @RequestBody @Valid MockPaymentActionRequest request) {
        return ResponseEntity.accepted().body(service.action(principal.getUserId(), paymentNo, request));
    }
}
