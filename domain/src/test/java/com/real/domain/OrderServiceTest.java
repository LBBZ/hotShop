package com.real.domain;

import com.real.common.exception.InventoryShortageException;
import com.real.domain.entity.Order;
import com.real.domain.entity.OrderItem;
import com.real.domain.entity.Product;
import com.real.domain.mapper.OrderMapper;
import com.real.domain.mapper.ProductMapper;
import com.real.domain.service.OrderService;
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

    private OrderService orderService;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this); // ��ʼ�� Mock ����
        orderService = new OrderService(orderMapper, productMapper);
    }

    // ���Է������������д
    private Order buildTestOrder() {
        Order order = new Order();
        order.setUserId(1L);

        // �������������
        OrderItem item1 = new OrderItem();
        item1.setProductId(1001L);
        item1.setQuantity(2);
        item1.setPrice(new BigDecimal("50.00")); // ������Ʒ���� 50 Ԫ

        OrderItem item2 = new OrderItem();
        item2.setProductId(1002L);
        item2.setQuantity(1);
        item2.setPrice(new BigDecimal("100.00")); // ������Ʒ���� 100 Ԫ

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
        // 1. ׼����������
        Order order = buildTestOrder();

        // 2. ģ�����ݿ����
        when(productMapper.reduceStock(any(Long.class), any(Integer.class))).thenReturn(1);
        when(productMapper.selectById(1001L)).thenReturn(new Product(1001L, "��ƷA", new BigDecimal("50.00"), 10, null, null, null));
        when(productMapper.selectById(1002L)).thenReturn(new Product(1002L, "��ƷB", new BigDecimal("100.00"), 5, null, null, null));

        // 3. ִ�в���
        String orderId = orderService.createOrder(order);

        // 4. ��֤���
        assertNotNull(orderId);
        verify(orderMapper, times(1)).insertOrder(any(Order.class));
        verify(orderMapper, times(2)).insertOrderItem(any(OrderItem.class));
    }

    @Test
    void testCreateOrder_InventoryShortage() {
        Order order = buildTestOrder();

        // ģ����Ʒ��治��
        Product product = new Product(1001L, "��ƷA", new BigDecimal("50.00"), 1, null, null, null);
        when(productMapper.selectById(1001L)).thenReturn(product);
        when(productMapper.reduceStock(1001L, 2)).thenReturn(0); // ��治�㷵�� 0

        // ��֤�Ƿ��׳��쳣
        assertThrows(InventoryShortageException.class, () -> orderService.createOrder(order));
    }

    @Test
    void testCreateOrder_RetryOnConflict() {
        Order order = buildTestOrder();

        // ģ����Ʒ����
        Product product1 = new Product(1001L, "��ƷA", new BigDecimal("50.00"), 10, null, null, null);
        Product product2 = new Product(1002L, "��ƷB", new BigDecimal("100.00"), 5, null, null, null);

        // �ؼ��޸���ģ�� selectById ����ֵ
        when(productMapper.selectById(1001L)).thenReturn(product1);
        when(productMapper.selectById(1002L)).thenReturn(product2);

        // ģ����ۼ���Ϊ����һ�γ�ͻ���ڶ��γɹ�
        when(productMapper.reduceStock(1001L, 2))
                .thenReturn(0)  // ��һ�γ�ͻ
                .thenReturn(1); // �ڶ��γɹ�
        when(productMapper.reduceStock(1002L, 1)).thenReturn(1);

        // ִ�в���֤
        assertDoesNotThrow(() -> orderService.createOrder(order));
        verify(productMapper, times(2)).reduceStock(1001L, 2); // ��֤���Դ���
    }

}