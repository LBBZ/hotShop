package com.real.common.api.dto;

import java.util.List;

public record AgentProductComparisonResponse(List<AgentProductSummaryResponse> products) {
    public AgentProductComparisonResponse {
        products = products == null ? List.of() : List.copyOf(products);
    }
}
