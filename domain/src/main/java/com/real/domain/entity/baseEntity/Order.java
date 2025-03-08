package com.real.domain.entity.baseEntity;

import com.real.common.enums.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "订单实体")
public class Order {
    @Schema(description = "订单号（UUID）", example = "ORDER_20231001123456")
    private String orderId;

    @Schema(description = "用户ID", example = "123")
    private Long userId;

    @Schema(description = "订单总金额（单位：元）", example = "13998.00")
    private BigDecimal totalAmount;

    @Schema(
            description = "订单状态",
            example = "PENDING",
            allowableValues = {"PENDING", "PAID", "SHIPPED", "COMPLETED", "CANCELLED"}
    )
    private OrderStatus status;

    @Schema(description = "创建时间", example = "2023-10-01T12:00:00")
    private LocalDateTime createdAt;

    @Schema(description = "订单项列表")
    private List<OrderItem> items;
}