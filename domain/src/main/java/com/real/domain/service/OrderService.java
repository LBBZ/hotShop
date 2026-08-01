package com.real.domain.service;

import com.github.pagehelper.PageInfo;
import com.real.common.api.CursorCodec;
import com.real.common.api.CursorSlice;
import com.real.common.enums.OrderStatus;
import com.real.common.util.PageHelperUtils;
import com.real.domain.entity.Order;
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
    private final PageHelperUtils<Order> pageHelperUtils;
    @Autowired
    public OrderService(OrderMapper orderMapper, PageHelperUtils<Order> pageHelperUtils) {
        this.orderMapper = orderMapper;
        this.pageHelperUtils = pageHelperUtils;
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

    public List<Order> getLegacyPendingOrdersBefore(LocalDateTime endTime) {
        return orderMapper.selectLegacyPendingOrdersBefore(endTime);
    }

    public PageInfo<Order> getOrdersByConditions(int pageNum, int pageSize, Long userId, OrderStatus status, LocalDateTime startTime, LocalDateTime endTime) {
        List<Order> orders;
        orders = getOrdersByConditions(userId, status, startTime, endTime);
        return pageHelperUtils.getPageInfo(pageNum, pageSize, orders);
    }

    public CursorSlice<Order> getUserOrdersByCursor(
            Long userId,
            int limit,
            String cursor,
            OrderStatus status,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        return getOrdersByCursor(
                "user-orders",
                userId,
                limit,
                cursor,
                status,
                startTime,
                endTime
        );
    }

    public CursorSlice<Order> getAdminOrdersByCursor(
            Long userId,
            int limit,
            String cursor,
            OrderStatus status,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        return getOrdersByCursor(
                "admin-orders",
                userId,
                limit,
                cursor,
                status,
                startTime,
                endTime
        );
    }

    private CursorSlice<Order> getOrdersByCursor(
            String scope,
            Long userId,
            int limit,
            String cursor,
            OrderStatus status,
            LocalDateTime startTime,
            LocalDateTime endTime
    ) {
        CursorCodec.TimeStringCursor decoded = CursorCodec.decodeTimeAndString(cursor, scope);
        List<Order> fetched = orderMapper.selectOrdersByCursor(
                userId,
                status,
                startTime,
                endTime,
                decoded == null ? null : decoded.time(),
                decoded == null ? null : decoded.id(),
                limit + 1
        );
        boolean hasMore = fetched.size() > limit;
        List<Order> items = hasMore ? List.copyOf(fetched.subList(0, limit)) : List.copyOf(fetched);
        String nextCursor = hasMore
                ? CursorCodec.encodeTimeAndString(
                        scope,
                        items.get(items.size() - 1).getCreatedAt(),
                        items.get(items.size() - 1).getOrderId()
                )
                : null;
        return new CursorSlice<>(items, nextCursor, hasMore);
    }
}
