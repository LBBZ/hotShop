package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

public record OutboxFailedPageResponse(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<OutboxFailedEventResponse> items,
        @Schema(nullable = true, description = "Opaque cursor for the next page; null on the final page")
        String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean hasMore
) {
    public OutboxFailedPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
