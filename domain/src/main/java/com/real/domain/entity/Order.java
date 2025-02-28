package com.real.domain.entity;

import com.real.common.enums.OrderStatus;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Order {

    private String id;          // 订单号（UUID）
    private Long userId;       // 用户ID
    private BigDecimal totalAmount;  // 总金额
    private OrderStatus status;      // 订单状态
    private LocalDateTime createdAt; // 创建时间
    private List<OrderItem> items;   // 订单项列表

}
