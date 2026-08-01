package com.real.task.timeoutOrderTask;

public interface OrderTimeoutConsumerFailpoint {
    default void afterCommitBeforeAck(OrderTimeoutService.TimeoutEvent event) { }
}
