package com.real.common.api.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

public record CursorPageResponse<T>(
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        List<T> items,
        @Schema(nullable = true, description = "Opaque cursor for the next page; null on the final page")
        String nextCursor,
        @Schema(requiredMode = Schema.RequiredMode.REQUIRED)
        boolean hasMore
) {
    public CursorPageResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
