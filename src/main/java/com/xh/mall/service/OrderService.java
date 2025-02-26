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
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class OrderService {

    @Autowired
    OrderMapper orderMapper;
    @Autowired
    ProductMapper productMapper;

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

    public List<Order> getOrdersByUserId(Long userId, int pageNum, int pageSize) {
        int offset = (pageNum - 1) * pageSize;
        return orderMapper.selectOrdersByUserId(userId, offset, pageSize);
    }

}
