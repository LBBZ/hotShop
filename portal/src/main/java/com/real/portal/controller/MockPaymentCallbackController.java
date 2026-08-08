package com.real.portal.controller;

import com.real.common.api.ApiException;
import com.real.common.api.dto.MockPaymentCallbackResponse;
import com.real.portal.payment.CallbackRejectedException;
import com.real.portal.payment.MockPaymentCallbackService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

@RestController
@Tag(name = "Mock Provider callback", description = "Local Mock Provider HMAC callback; not a real payment service")
public class MockPaymentCallbackController {
    private final MockPaymentCallbackService service;
    public MockPaymentCallbackController(MockPaymentCallbackService service) { this.service = service; }

    @Operation(summary = "Accept a signed local Mock Provider callback")
    @PostMapping(path = "/provider-callbacks/v1/mock-payment", consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public MockPaymentCallbackResponse callback(
            @Parameter(required = true) @RequestHeader(value = "X-Mock-Timestamp", required = false) String timestamp,
            @Parameter(required = true) @RequestHeader(value = "X-Mock-Nonce", required = false) String nonce,
            @Parameter(required = true) @RequestHeader(value = "X-Mock-Signature", required = false) String signature,
            @RequestBody byte[] rawBody, HttpServletRequest request) {
        try {
            return service.accept(timestamp, nonce, signature, rawBody, request);
        } catch (CallbackRejectedException rejected) {
            service.auditRejected(rejected, request);
            throw new ApiException(rejected.status(), "MOCK_CALLBACK_REJECTED",
                    "Mock callback rejected", "The Mock Provider callback was rejected");
        }
    }
}
