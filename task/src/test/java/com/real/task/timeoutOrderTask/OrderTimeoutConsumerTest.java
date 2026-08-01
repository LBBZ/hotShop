package com.real.task.timeoutOrderTask;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class OrderTimeoutConsumerTest {
    private final ObjectMapper json = new ObjectMapper();
    private final OrderTimeoutService service = mock(OrderTimeoutService.class);
    private final Channel channel = mock(Channel.class);

    @Test
    void acknowledgesOnlyAfterBusinessMethodReturns() throws Exception {
        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(
                json, service, new OrderTimeoutConsumerFailpoint() { });
        Message message = message(validEnvelope(), 41L);

        consumer.consume(message, channel);

        var ordered = inOrder(service, channel);
        ordered.verify(service).process(any());
        ordered.verify(channel).basicAck(41L, false);
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void databaseFailureLeavesMessageUnacknowledgedForBrokerRedelivery() throws Exception {
        doThrow(new IllegalStateException("database unavailable")).when(service).process(any());
        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(
                json, service, new OrderTimeoutConsumerFailpoint() { });

        assertThatThrownBy(() -> consumer.consume(message(validEnvelope(), 42L), channel))
                .isInstanceOf(IllegalStateException.class);

        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    @Test
    void poisonMessageIsRejectedWithoutRequeueSoItDeadLetters() throws Exception {
        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(
                json, service, new OrderTimeoutConsumerFailpoint() { });
        String poison = validEnvelope().replace("\"schemaVersion\":1", "\"schemaVersion\":2");

        consumer.consume(message(poison, 43L), channel);

        verify(channel).basicReject(43L, false);
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verifyNoInteractions(service);
    }

    @Test
    void crashAfterCommitBeforeAckLeavesMessageForInboxDeduplication() throws Exception {
        OrderTimeoutConsumer consumer = new OrderTimeoutConsumer(json, service,
                new OrderTimeoutConsumerFailpoint() {
                    @Override
                    public void afterCommitBeforeAck(OrderTimeoutService.TimeoutEvent event) {
                        throw new SimulatedCrash();
                    }
                });

        assertThatThrownBy(() -> consumer.consume(message(validEnvelope(), 44L), channel))
                .isInstanceOf(SimulatedCrash.class);

        verify(service).process(any());
        verify(channel, never()).basicAck(anyLong(), anyBoolean());
        verify(channel, never()).basicReject(anyLong(), anyBoolean());
    }

    private static Message message(String body, long deliveryTag) {
        MessageProperties properties = new MessageProperties();
        properties.setDeliveryTag(deliveryTag);
        return new Message(body.getBytes(StandardCharsets.UTF_8), properties);
    }

    private static String validEnvelope() {
        return """
                {
                  "schemaVersion":1,
                  "eventId":"39d89067-5dcf-4f96-9658-e97644dbd16f",
                  "eventType":"LEGACY_ORDER_TIMEOUT_REQUESTED",
                  "aggregateType":"ORDER",
                  "aggregateId":"order-1",
                  "occurredAt":"2026-08-01T00:00:00Z",
                  "payload":{
                    "schemaVersion":1,
                    "orderId":"order-1",
                    "userId":10,
                    "amount":"99.00",
                    "currency":"CNY",
                    "expiresAtMs":1785542400000
                  }
                }
                """;
    }

    private static final class SimulatedCrash extends RuntimeException { }
}
