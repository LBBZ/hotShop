package com.xh.mall.service;

import com.xh.mall.entity.Product;
import com.xh.mall.mapper.ProductMapper;
import com.xh.mall.util.OrderStatus;
import com.xh.mall.util.RetryUtils;
import com.xh.mall.util.exception.InventoryShortageException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.xh.mall.mapper.OrderMapper;
import com.xh.mall.entity.Order;
import com.xh.mall.entity.OrderItem;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrderService {

    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;

    @Autowired
    public OrderService(OrderMapper orderMapper, ProductMapper productMapper) {
        this.orderMapper = orderMapper;
        this.productMapper = productMapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryCreateOrder(Order order) {
        // 1. 生成订单号（幂等性保障：订单号在重试中保持不变）
        if (order.getId() == null) {
            order.setId(UUID.randomUUID().toString());
            order.setStatus(OrderStatus.PENDING);
        }

        // 2. 扣减库存（乐观锁）
        for (OrderItem item : order.getItems()) {
            int rows = productMapper.reduceStock(item.getProductId(), item.getQuantity());
            if (rows == 0) {
                throw new InventoryShortageException("库存不足或并发冲突: 商品ID " + item.getProductId());
            }
        }

        // 3. 计算总金额
        BigDecimal totalAmount = order.getItems().stream()
                .map(item -> {
                    Product product = productMapper.selectById(item.getProductId());
                    item.setPrice(product.getPrice());
                    return product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity()));
                })
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        order.setTotalAmount(totalAmount);

        // 4. 保存订单和订单项
        orderMapper.insertOrder(order);
        order.getItems().forEach(item -> {
            item.setOrderId(order.getId());
            orderMapper.insertOrderItem(item);
        });
        return true;
    }

    // 外层调用入口
    public String createOrder(Order order) {
        RetryUtils.executeWithRetry(
                () -> tryCreateOrder(order),  // 调用带事务的方法
                3,      // 最大重试次数
                200     // 重试间隔 200ms
        );
        return order.getId();
    }

    public Order getOrderById(String orderId) {
        return orderMapper.selectOrderById(orderId);
    }

    /**
     * 分页查询用户的订单及每个订单的分页订单项
     * @param userId 用户ID
     * @param orderPage 订单页码（从1开始）
     * @param orderPageSize 每页订单数量
     * @param itemPage 订单项页码（从1开始）
     * @param itemPageSize 每页订单项数量
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

}
