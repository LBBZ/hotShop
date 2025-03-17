package com.real.domain.entity;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "订单项实体")
public class OrderItem {
    @Schema(description = "订单项ID", example = "789")
    private Long orderItemId;

    @Schema(description = "关联订单号", example = "ORDER_20231001123456")
    private String orderId;

    @Schema(description = "商品ID", example = "456")
    private Long productId;

    @Schema(description = "购买数量", example = "2")
    private Integer quantity;

    @Schema(description = "商品单价（单位：元）", example = "6999.00")
    private BigDecimal price;
}