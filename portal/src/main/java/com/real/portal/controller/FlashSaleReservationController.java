package com.real.portal.controller;

import com.real.common.api.ApiException;
import com.real.common.api.RequestContext;
import com.real.common.api.dto.FlashSaleReservationRequest;
import com.real.common.api.dto.FlashSaleReservationResponse;
import com.real.domain.service.seckill.FlashSaleReservationResult;
import com.real.domain.service.seckill.FlashSaleReservationService;
import com.real.security.entity.CustomUserDetails;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.headers.Header;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@Tag(name = "User flash-sale reservations", description = "Low-latency Redis Reservation intake")
@RequestMapping("/api/v1/flash-sales")
@PreAuthorize("hasAuthority('ROLE_USER')")
@SecurityRequirement(name = "bearerAuth")
public class FlashSaleReservationController {
    private final FlashSaleReservationService reservationService;

    public FlashSaleReservationController(FlashSaleReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @Operation(
            summary = "Reserve flash-sale inventory",
            description = "Atomically reserves Redis inventory and appends one Reservation event. "
                    + "No MySQL Order is created by this request."
    )
    @ApiResponse(
            responseCode = "202",
            description = "Reservation accepted",
            headers = @Header(
                    name = "Idempotency-Replayed",
                    description = "Present and true when the original accepted result is replayed",
                    schema = @Schema(type = "boolean")
            ),
            content = @Content(
                    mediaType = MediaType.APPLICATION_JSON_VALUE,
                    schema = @Schema(implementation = FlashSaleReservationResponse.class)
            )
    )
    @PostMapping("/{activityId}/reservations")
    public ResponseEntity<FlashSaleReservationResponse> reserve(
            @PathVariable @Min(1) Long activityId,
            @RequestHeader("Idempotency-Key")
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$")
            @Parameter(
                    required = true,
                    schema = @Schema(
                            type = "string",
                            pattern = "^[A-Za-z0-9][A-Za-z0-9._:-]{15,127}$",
                            minLength = 16,
                            maxLength = 128
                    )
            )
            String idempotencyKey,
            @RequestBody @Valid FlashSaleReservationRequest request,
            @AuthenticationPrincipal CustomUserDetails principal,
            HttpServletRequest servletRequest
    ) {
        String requestId = RequestContext.requestId(servletRequest);
        FlashSaleReservationResult result = reservationService.reserve(
                activityId,
                principal.getUserId(),
                request.quantity(),
                idempotencyKey,
                requestId
        );
        if (!result.accepted()) {
            throw problem(result);
        }
        ResponseEntity.BodyBuilder response = ResponseEntity.status(HttpStatus.ACCEPTED);
        if (result.replayed()) {
            response.header("Idempotency-Replayed", "true");
        }
        return response.body(new FlashSaleReservationResponse(
                result.reservationNo(),
                result.activityId(),
                result.status(),
                result.requestId()
        ));
    }

    private ApiException problem(FlashSaleReservationResult result) {
        return switch (result.code()) {
            case IDEMPOTENCY_CONFLICT -> ApiException.conflict(
                    "IDEMPOTENCY_KEY_CONFLICT",
                    "Idempotency-Key is already bound to a different request"
            );
            case ACTIVITY_NOT_FOUND -> new ApiException(
                    HttpStatus.NOT_FOUND,
                    "FLASH_SALE_ACTIVITY_NOT_FOUND",
                    "Resource not found",
                    "Flash Sale Activity was not found"
            );
            case ACTIVITY_NOT_STARTED -> ApiException.conflict(
                    "FLASH_SALE_NOT_STARTED",
                    "Flash Sale Activity has not started"
            );
            case ACTIVITY_ENDED -> ApiException.conflict(
                    "FLASH_SALE_ENDED",
                    "Flash Sale Activity has ended"
            );
            case ACTIVITY_NOT_ACTIVE -> ApiException.conflict(
                    "FLASH_SALE_NOT_ACTIVE",
                    "Flash Sale Activity is not active"
            );
            case SOLD_OUT -> ApiException.conflict(
                    "FLASH_SALE_SOLD_OUT",
                    "Flash Sale Activity inventory is exhausted"
            );
            case USER_LIMIT_REACHED -> ApiException.conflict(
                    "FLASH_SALE_USER_LIMIT_REACHED",
                    "The requested quantity exceeds the per-User limit or the User already "
                            + "has an effective Reservation for this activity"
            );
            case INVALID_QUANTITY -> ApiException.badRequest(
                    "FLASH_SALE_INVALID_QUANTITY",
                    "Quantity is invalid for this activity"
            );
            case INTERNAL_STATE_INVALID -> ApiException.serviceUnavailable(
                    "SECKILL_STATE_INVALID",
                    "Flash-sale state is temporarily unavailable"
            );
            case ACCEPTED, IDEMPOTENT_REPLAY ->
                    throw new IllegalArgumentException("Accepted result cannot be mapped to a problem");
        };
    }
}
