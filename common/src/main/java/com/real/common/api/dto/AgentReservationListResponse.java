package com.real.common.api.dto;

import java.util.List;

public record AgentReservationListResponse(List<AgentReservationSummaryResponse> items) {
    public AgentReservationListResponse {
        items = items == null ? List.of() : List.copyOf(items);
    }
}
