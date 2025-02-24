package com.xh.mall.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.xh.mall.entity.Order;
import com.xh.mall.entity.OrderItem;

import java.util.List;

@Mapper
public interface OrderMapper {

    void insertOrder(Order order);
    void insertOrderItem(OrderItem item);
    Order selectOrderById(String orderId);
    List<Order> selectOrdersByUserId(Long userId);

    void insertOrderItems(List<OrderItem> items);

}
