package com.xh.mall.util.test;

import com.xh.mall.entity.Order;
import com.xh.mall.entity.OrderItem;
import com.xh.mall.util.OrderStatus;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

public class buildTestOrder {
    public static Order buildTestOrder() {
        // 创建订单项列表
        List<OrderItem> items = new ArrayList<>();
        OrderItem item1 = new OrderItem();
        item1.setProductId(1L);  // 商品ID
        item1.setQuantity(100);     // 购买数量
        item1.setPrice(new BigDecimal("100.00"));  // 商品单价
        items.add(item1);

        OrderItem item2 = new OrderItem();
        item2.setProductId(2L);
        item2.setQuantity(200);
        item2.setPrice(new BigDecimal("200.00"));
        items.add(item2);

        // 创建订单
        Order order = new Order();
        order.setUserId(1L);  // 用户ID
        order.setTotalAmount(new BigDecimal("400.00"));  // 总金额
        order.setStatus(OrderStatus.PENDING);  // 订单状态：待支付
        order.setItems(items);  // 设置订单项列表

        return order;
    }
}
