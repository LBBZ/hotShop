package com.real.domain.service.baseService;

import com.real.common.enums.OrderStatus;
import com.real.domain.entity.baseEntity.Order;
import com.real.domain.mapper.OrderMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Service
@Transactional
public class OrderService {

    private final OrderMapper orderMapper;

    @Autowired
    public OrderService(OrderMapper orderMapper) {
        this.orderMapper = orderMapper;
    }

    public Order getOrderById(String orderId) {
        return orderMapper.selectOrderById(orderId);
    }

    /**
     * 根据状态查询订单
     */
    public List<Order> getOrdersByStatus(OrderStatus status) {
        return orderMapper.selectOrdersByOrderStatus(status);
    }

    /**
     * 分页查询用户的订单及每个订单的分页订单项
     * @return 分页后的订单列表（每个订单包含分页的订单项）
     */
    public List<Order> getOrdersByUserId(Long userId,
                                         int orderPage,
                                         int orderPageSize,
                                         int itemPage,
                                         int itemPageSize) {
        // 1. 计算分页偏移量
        int orderOffset = (orderPage - 1) * orderPageSize;
        int itemOffset = (itemPage - 1) * itemPageSize;

        // 2. 调用Mapper查询数据
        List<Order> orders = orderMapper.selectOrdersByUserId(
                userId,
                orderOffset,
                orderPageSize,
                itemOffset,
                itemPageSize
        );

        // 3. 处理可能的空结果
        if (orders == null) {
            return Collections.emptyList();
        }

        // 4. 返回分页结果
        return orders;
    }

    public List<Order> getOrdersByConditions(Long userId, OrderStatus status, LocalDateTime startTime, LocalDateTime endTime) {
        return orderMapper.selectOrdersByConditions(userId, status, startTime, endTime);
    }
}
