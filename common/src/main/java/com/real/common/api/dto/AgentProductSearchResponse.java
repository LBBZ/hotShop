package com.real.common.api.dto;

import java.util.List;

public record AgentProductSearchResponse(
        List<AgentProductSummaryResponse> items,
        String nextCursor,
        boolean hasMore
) {
    public AgentProductSearchResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
