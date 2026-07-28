package com.real.task.timeoutOrderTask;

import com.real.common.enums.OrderStatus;
import com.real.domain.service.OrderService;
import com.real.domain.service.advance.OrderStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OrderTimeoutJobUtcTest {
    @Mock
    private OrderService orderService;
    @Mock
    private OrderStateService orderStateService;

    @Test
    void timeoutThresholdUsesUtcIndependentlyOfTheHostDefaultTimeZone() {
        Clock clock = Clock.fixed(Instant.parse("2026-07-28T08:30:00Z"), ZoneOffset.UTC);
        OrderTimeoutJob job = new OrderTimeoutJob(orderService, orderStateService, clock);
        ReflectionTestUtils.setField(job, "timeoutThreshold", 30L);
        LocalDateTime expectedThreshold = LocalDateTime.of(2026, 7, 28, 8, 0);
        when(orderService.getOrdersByConditions(
                null,
                OrderStatus.PENDING,
                null,
                expectedThreshold
        )).thenReturn(List.of());

        job.checkTimeoutOrders();

        verify(orderService).getOrdersByConditions(
                null,
                OrderStatus.PENDING,
                null,
                expectedThreshold
        );
    }
}
