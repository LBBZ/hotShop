package com.real.task.timeoutOrderTask;

import com.real.domain.infra.RedisService;
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
    private OrderStateService orderStateService;

    private OrderTimeoutConsumer consumer;

    @BeforeEach
    void setUp() {
        consumer = new OrderTimeoutConsumer(redisService, orderStateService);
    }

    @Test
    void acquiredLockRequestsLegacyOnlyCancellationThenDeletesTheLock() {
        when(redisService.setWithTTL("lock:order:order-1", true, 15, 10))
                .thenReturn(true);

        consumer.handleOrderTimeout("order-1");

        InOrder actions = inOrder(orderStateService, redisService);
        actions.verify(orderStateService).cancelLegacyPendingOrder("order-1");
        actions.verify(redisService).delete("lock:order:order-1", 15);
    }

    @Test
    void unavailableLockCurrentlySkipsOrderLookupAndCancellation() {
        when(redisService.setWithTTL("lock:order:order-1", true, 15, 10))
                .thenReturn(false);

        consumer.handleOrderTimeout("order-1");

        verifyNoInteractions(orderStateService);
        verify(redisService, never()).delete("lock:order:order-1", 15);
    }

    @Test
    void legacyCancellationNoOpStillDeletesTheLock() {
        when(redisService.setWithTTL("lock:order:order-1", true, 15, 10))
                .thenReturn(true);
        when(orderStateService.cancelLegacyPendingOrder("order-1")).thenReturn(false);

        consumer.handleOrderTimeout("order-1");

        verify(orderStateService).cancelLegacyPendingOrder("order-1");
        verify(redisService).delete("lock:order:order-1", 15);
    }
}
