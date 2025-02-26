package com.xh.mall.mapper;

import org.apache.ibatis.annotations.Mapper;

import com.xh.mall.entity.Order;
import com.xh.mall.entity.OrderItem;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface OrderMapper {

    int insertOrder(Order order);
    int updateOrder(Order order);
    Order selectOrderById(String orderId);
    // 分页查询用户订单
    List<Order> selectOrdersByUserId(@Param("userId") Long userId,
                                     @Param("offset") int offset,
                                     @Param("limit") int limit);

    int insertOrderItem(OrderItem item);
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insertOrderItems(List<OrderItem> items);

}
