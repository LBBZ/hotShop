package com.real.task.timeoutOrderTask;

import com.real.common.enums.OrderStatus;
import com.real.domain.entity.Order;
import com.real.domain.infra.RedisService;
import com.real.domain.service.OrderService;
import com.real.domain.service.advance.OrderStateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutConsumerCharacterizationTest {

    @Mock
    private RedisService redisService;
    @Mock
    private OrderService orderService;
    @Mock
    private OrderStateService orderStateService;

    private OrderTimeoutConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderTimeoutConsumer(redisService, orderService, orderStateService);
    }

    @Test
    void acquiredLockCurrentlyCancelsPendingOrderThenDeletesTheLock() {
        Order order = new Order();
        order.setStatus(OrderStatus.PENDING);
        when(redisService.setWithTTL("lock:order:order-1", true, 15, 10))
                .thenReturn(true);
        when(orderService.getOrderById("order-1")).thenReturn(order);

        consumer.handleOrderTimeout("order-1");

        InOrder actions = inOrder(orderService, orderStateService, redisService);
        actions.verify(orderService).getOrderById("order-1");
        actions.verify(orderStateService).cancelOrder("order-1");
        actions.verify(redisService).delete("lock:order:order-1", 15);
    }

    @Test
    void unavailableLockCurrentlySkipsOrderLookupAndCancellation() {
        when(redisService.setWithTTL("lock:order:order-1", true, 15, 10))
                .thenReturn(false);

        consumer.handleOrderTimeout("order-1");

        verifyNoInteractions(orderService, orderStateService);
        verify(redisService, never()).delete("lock:order:order-1", 15);
    }

    @Test
    void acquiredLockForNonPendingOrderCurrentlyOnlyDeletesTheLock() {
        Order order = new Order();
        order.setStatus(OrderStatus.PAID);
        when(redisService.setWithTTL("lock:order:order-1", true, 15, 10))
                .thenReturn(true);
        when(orderService.getOrderById("order-1")).thenReturn(order);

        consumer.handleOrderTimeout("order-1");

        verify(orderStateService, never()).cancelOrder("order-1");
        verify(redisService).delete("lock:order:order-1", 15);
    }
}
