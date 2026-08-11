package com.real.common.api.dto;

import java.util.List;

public record AgentOrderListResponse(
        List<AgentOrderSummaryResponse> items,
        String nextCursor,
        boolean hasMore
) {
    public AgentOrderListResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
