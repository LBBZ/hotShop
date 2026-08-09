package com.real.portal.controller;

import com.real.portal.sse.TransactionEventStreamService;
import com.real.common.api.ApiException;
import com.real.security.entity.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@RestController
@Validated
@PreAuthorize("hasAuthority('ROLE_USER')")
@SecurityRequirement(name = "bearerAuth")
@Tag(name = "User transaction events", description = "Authenticated durable SSE timelines")
public class TransactionEventController {
    private final TransactionEventStreamService streamService;

    public TransactionEventController(TransactionEventStreamService streamService) {
        this.streamService = streamService;
    }

    @Operation(summary = "Stream my Order timeline", description = "Supports Last-Event-ID recovery")
    @GetMapping(value = "/api/v1/orders/{orderId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter orderEvents(
            @PathVariable @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String orderId,
            @RequestHeader(name = "Last-Event-ID", required = false)
            @Pattern(regexp = "[0-9]{1,19}") String lastEventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return streamService.order(orderId, principal.getUserId(), parse(lastEventId));
    }

    @Operation(summary = "Stream my Flash Sale Reservation timeline",
            description = "Supports Last-Event-ID recovery")
    @GetMapping(
            value = "/api/v1/flash-sales/{activityId}/reservations/{reservationNo}/events",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE
    )
    public SseEmitter reservationEvents(
            @PathVariable @Min(1) Long activityId,
            @PathVariable @Pattern(regexp = "^rsv_[0-9a-f]{32}$") String reservationNo,
            @RequestHeader(name = "Last-Event-ID", required = false)
            @Pattern(regexp = "[0-9]{1,19}") String lastEventId,
            @AuthenticationPrincipal CustomUserDetails principal
    ) {
        return streamService.reservation(
                activityId, reservationNo, principal.getUserId(), parse(lastEventId)
        );
    }

    private static long parse(String value) {
        if (value == null) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw ApiException.badRequest("LAST_EVENT_ID_INVALID", "Last-Event-ID is out of range");
        }
    }
}
