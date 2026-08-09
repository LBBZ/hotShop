package com.real.portal.controller;

import com.real.common.api.dto.FlashSaleActivityResponse;
import com.real.portal.userjourney.FlashSaleActivityQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@Validated
@Tag(name = "Public Flash Sale Activities", description = "Anonymous current and upcoming sale windows")
@RequestMapping("/api/v1/flash-sale-activities")
public class FlashSaleActivityController {
    private final FlashSaleActivityQueryService service;

    public FlashSaleActivityController(FlashSaleActivityQueryService service) {
        this.service = service;
    }

    @Operation(summary = "List current and upcoming Flash Sale Activities")
    @GetMapping
    public List<FlashSaleActivityResponse> list(
            @RequestParam(defaultValue = "12") @Min(1) @Max(50) int limit
    ) {
        return service.currentAndUpcoming(limit);
    }
}
