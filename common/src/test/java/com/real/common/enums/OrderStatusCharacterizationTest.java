package com.real.common.enums;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OrderStatusCharacterizationTest {

    @Test
    void pendingCurrentlyTransitionsOnlyToPaidOrCanceled() {
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.PAID));
        assertTrue(OrderStatus.PENDING.canTransitionTo(OrderStatus.CANCELED));
        assertFalse(OrderStatus.PENDING.canTransitionTo(OrderStatus.SHIPPED));
        assertFalse(OrderStatus.PENDING.canTransitionTo(OrderStatus.COMPLETED));
    }

    @Test
    void completedAndCanceledAreCurrentlyTerminal() {
        for (OrderStatus next : EnumSet.allOf(OrderStatus.class)) {
            assertFalse(OrderStatus.COMPLETED.canTransitionTo(next));
            assertFalse(OrderStatus.CANCELED.canTransitionTo(next));
        }
    }

    @Test
    void persistedEnglishStatusNamesRoundTripThroughTheCurrentParser() {
        for (OrderStatus status : EnumSet.allOf(OrderStatus.class)) {
            assertEquals(status, OrderStatus.strTransitionToEnums(status.name()));
        }
    }
}
