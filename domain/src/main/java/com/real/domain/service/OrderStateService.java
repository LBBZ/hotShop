package com.real.domain.service;

import com.real.common.enums.OrderStatus;
import com.real.domain.entity.Order;
import com.real.domain.mapper.OrderMapper;
import com.real.domain.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// OrderStateService.java (领域服务)
@Service
public class OrderStateService {

    private final ProductMapper productMapper;
    private final OrderMapper orderMapper;

    @Autowired
    public OrderStateService(ProductMapper productMapper, OrderMapper orderMapper) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
    }
    /**
     * 订单状态流转（如支付、取消）
     * 包含数据库stock和order更新操作
     */
    public void changeOrderStatus(Order order, OrderStatus newStatus) {
        // 校验状态流转合法性（如"待支付"只能转"已支付"或"已取消"）
        if (!order.getStatus().canTransitionTo(newStatus)) {
            throw new IllegalStateException("订单状态流转非法");
        }
        order.setStatus(newStatus);
        orderMapper.updateOrder(order);
        // 触发状态变更事件（如库存释放）
        if (newStatus == OrderStatus.CANCELED) {
            releaseStock(order);
        }
    }

    /**
     * 释放库存（领域逻辑）
     */
    private void releaseStock(Order order) {
        order.getItems().forEach(item ->
                productMapper.increaseStock(item.getProductId(), item.getQuantity())
        );
    }

}