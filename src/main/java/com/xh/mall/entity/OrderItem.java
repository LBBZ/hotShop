package com.xh.mall.entity;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class OrderItem {

    private Long id;
    private String orderId;     // 关联订单号
    private Long productId;     // 商品ID
    private Integer quantity;   // 购买数量
    private BigDecimal price;   // 商品单价

}