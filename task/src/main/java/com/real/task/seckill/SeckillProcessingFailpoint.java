package com.real.task.seckill;

/**
 * Test-only fault injection seam. Production supplies the no-op implementation
 * below and exposes no HTTP or remote trigger.
 */
public interface SeckillProcessingFailpoint {
    default void beforeOrderCommit(ReservationAcceptedEvent event) {
    }

    default void afterOrderCommitBeforeFinalize(ReservationAcceptedEvent event) {
    }

    default void afterFinalizeBeforeAck(ReservationAcceptedEvent event) {
    }

    default void afterCompensationIntent(ReservationAcceptedEvent event) {
    }

    default void afterRedisCompensation(ReservationAcceptedEvent event) {
    }

    default void beforeCompensationCommit(ReservationAcceptedEvent event) {
    }
}
