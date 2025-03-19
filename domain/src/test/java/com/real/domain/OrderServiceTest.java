package com.real.domain;

import com.real.common.exception.InventoryShortageException;
import com.real.common.util.PageHelperUtils;
import com.real.domain.entity.Order;
import com.real.domain.entity.OrderItem;
import com.real.domain.entity.Product;
import com.real.domain.mapper.OrderMapper;
import com.real.domain.mapper.ProductMapper;
import com.real.domain.service.OrderService;
import com.real.domain.service.advance.OrderStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.times;

class OrderServiceTest {
    @Mock
    private ProductMapper productMapper;
    @Mock
    private OrderMapper orderMapper;
    private OrderStateService orderStateService;
    private OrderService orderService;
    @Mock
    private PageHelperUtils<Order> pageHelperUtils = new PageHelperUtils<>();
    @BeforeEach
    void setUp() throws Exception {
        try (AutoCloseable ignored = MockitoAnnotations.openMocks(this)) {
            // 初始化 Mock 对象
            orderService = new OrderService(orderMapper, pageHelperUtils);
            orderStateService = new OrderStateService(orderMapper, productMapper);
        }
    }

    // 测试方法将在这里编写
    private Order buildTestOrder() {
        Order order = new Order();
        order.setUserId(1L);

        // 添加两个订单项
        OrderItem item1 = new OrderItem();
        item1.setProductId(1001L);
        item1.setQuantity(2);
        item1.setPrice(new BigDecimal("50.00")); // 假设商品单价 50 元

        OrderItem item2 = new OrderItem();
        item2.setProductId(1002L);
        item2.setQuantity(1);
        item2.setPrice(new BigDecimal("100.00")); // 假设商品单价 100 元

        order.setItems(Arrays.asList(item1, item2));
        return order;
    }

    @Test
    void testDependencyInjection() {
        assertNotNull(orderService);
        assertNotNull(orderMapper);
        assertNotNull(productMapper);
    }

    @Test
    void testCreateOrder_Success() {
        // 1. 准备测试数据
        Order order = buildTestOrder();

        // 2. 模拟数据库操作
        when(productMapper.reduceStock(any(Long.class), any(Integer.class))).thenReturn(1);
        when(productMapper.selectById(1001L)).thenReturn(new Product(1001L, "商品A", new BigDecimal("50.00"), 10, null, null, null));
        when(productMapper.selectById(1002L)).thenReturn(new Product(1002L, "商品B", new BigDecimal("100.00"), 5, null, null, null));

        // 3. 执行测试
        String orderId = orderStateService.createOrder(order);

        // 4. 验证结果
        assertNotNull(orderId);
        verify(orderMapper, times(1)).insertOrder(any(Order.class));
        verify(orderMapper, times(2)).insertOrderItem(any(OrderItem.class));
    }

    @Test
    void testCreateOrder_InventoryShortage() {
        Order order = buildTestOrder();

        // 模拟商品库存不足
        Product product = new Product(1001L, "商品A", new BigDecimal("50.00"), 1, null, null, null);
        when(productMapper.selectById(1001L)).thenReturn(product);
        when(productMapper.reduceStock(1001L, 2)).thenReturn(0); // 库存不足返回 0

        // 验证是否抛出异常
        assertThrows(InventoryShortageException.class, () -> orderStateService.createOrder(order));
    }

    @Test
    void testCreateOrder_RetryOnConflict() {
        Order order = buildTestOrder();

        // 模拟商品数据
        Product product1 = new Product(1001L, "商品A", new BigDecimal("50.00"), 10, null, null, null);
        Product product2 = new Product(1002L, "商品B", new BigDecimal("100.00"), 5, null, null, null);

        // 关键修复：模拟 selectById 返回值
        when(productMapper.selectById(1001L)).thenReturn(product1);
        when(productMapper.selectById(1002L)).thenReturn(product2);

        // 模拟库存扣减行为：第一次冲突，第二次成功
        when(productMapper.reduceStock(1001L, 2))
                .thenReturn(0)  // 第一次冲突
                .thenReturn(1); // 第二次成功
        when(productMapper.reduceStock(1002L, 1)).thenReturn(1);

        // 执行并验证
        assertDoesNotThrow(() -> orderStateService.createOrder(order));
        verify(productMapper, times(2)).reduceStock(1001L, 2); // 验证重试次数
    }

}