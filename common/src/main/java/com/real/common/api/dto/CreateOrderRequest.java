package com.real.common.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record CreateOrderRequest(
        @NotEmpty
        @Size(max = 100)
        List<@Valid CreateOrderItemRequest> items
) {
}
