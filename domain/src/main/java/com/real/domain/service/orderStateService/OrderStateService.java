package com.real.domain.service.orderStateService;

import com.real.common.enums.OrderStatus;
import com.real.common.exception.InventoryShortageException;
import com.real.common.util.RetryUtils;
import com.real.domain.entity.baseEntity.Order;
import com.real.domain.entity.baseEntity.OrderItem;
import com.real.domain.entity.baseEntity.Product;
import com.real.domain.mapper.OrderMapper;
import com.real.domain.mapper.ProductMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.UUID;

// OrderStateService.java (领域服务)
@Service
public class OrderStateService {

    private final OrderMapper orderMapper;
    private final ProductMapper productMapper;

    @Autowired
    public OrderStateService(OrderMapper orderMapper, ProductMapper productMapper) {
        this.productMapper = productMapper;
        this.orderMapper = orderMapper;
    }

    /**
     * 订单状态流转（如支付、取消）
     * 包含数据库stock和order更新操作
     */
    @Transactional
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

    /**
     * 创建订单
     */
    public String createOrder(Order order) {
        RetryUtils.executeWithRetry(
                () -> tryCreateOrder(order),  // 调用带事务的方法
                3,      // 最大重试次数
                200     // 重试间隔 200ms
        );
        return order.getId();
    }

    /**
     * 支付订单
     */
    @Transactional
    public void payOrder(String orderId) {
        Order order = orderMapper.selectOrderById(orderId);
        if (order.getStatus().canTransitionTo(OrderStatus.PAID)) {
            order.setStatus(OrderStatus.PAID);
            orderMapper.updateOrder(order);
        }
        throw new IllegalStateException("订单当前状态不可支付");
    }

    /**
     * 取消订单
     */
    @Transactional
    public void cancelOrder(String orderId) {
        Order order = orderMapper.selectOrderById(orderId);
        if (order.getStatus().canTransitionTo(OrderStatus.CANCELED)) {
            order.setStatus(OrderStatus.CANCELED);
            orderMapper.updateOrder(order);
            // 释放库存
            releaseStock(order);
        }
        throw new IllegalStateException("订单当前状态不可取消");
    }

    /**
     * 完成订单
     */
    @Transactional
    public void completeOrder(String orderId) {
        Order order = orderMapper.selectOrderById(orderId);
        if (order.getStatus().canTransitionTo(OrderStatus.COMPLETED)) {
            order.setStatus(OrderStatus.COMPLETED);
            orderMapper.updateOrder(order);
        }
        throw new IllegalStateException("订单当前状态不可取消");
    }

    /**
     * 释放库存
     */
    private void releaseStock(Order order) {
        order.getItems().forEach(item ->
                productMapper.increaseStock(item.getProductId(), item.getQuantity())
        );
    }

}