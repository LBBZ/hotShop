package com.real.task.outbox;

/** Test-only process-crash boundary. The publisher must never translate it into a business failure. */
public final class OutboxPublisherCrashException extends RuntimeException {
    public OutboxPublisherCrashException(String boundary) { super(boundary); }
}
