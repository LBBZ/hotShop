package com.real.task.outbox;

public final class OutboxLeaseLostException extends RuntimeException {
    public OutboxLeaseLostException(String eventId) { super("Outbox lease lost for event " + eventId); }
}
