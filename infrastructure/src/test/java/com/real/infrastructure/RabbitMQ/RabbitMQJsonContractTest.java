package com.real.infrastructure.RabbitMQ;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RabbitMQJsonContractTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final MessageConverter converter = new RabbitMQConfig(Duration.ofMinutes(15)).jsonMessageConverter();

    @Test
    void writesOutboxEnvelopeWithNestedJsonPayloadWithoutChangingWireTypes() {
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("schemaVersion", 1);
        envelope.put("eventId", "event-1");
        envelope.put("eventType", "ORDER_CREATED");
        envelope.put("aggregateType", "ORDER");
        envelope.put("aggregateId", "9223372036854775807");
        envelope.put("occurredAt", "2026-10-01T00:00:00Z");
        envelope.put("payload", mapper.readTree(
                "{\"orderId\":\"9223372036854775807\",\"totalAmount\":\"0.20\",\"quantity\":2}"));

        Message message = converter.toMessage(envelope, new MessageProperties());
        JsonNode json = mapper.readTree(message.getBody());
        Object restored = converter.fromMessage(message);
        JsonNode restoredJson = mapper.valueToTree(restored);

        assertThat(converter).isInstanceOf(JacksonJsonMessageConverter.class);
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(json.get("schemaVersion").intValue()).isEqualTo(1);
        assertThat(json.get("aggregateId").stringValue()).isEqualTo("9223372036854775807");
        assertThat(json.at("/payload/orderId").stringValue()).isEqualTo("9223372036854775807");
        assertThat(json.at("/payload/totalAmount").stringValue()).isEqualTo("0.20");
        assertThat(json.at("/payload/quantity").intValue()).isEqualTo(2);
        assertThat(restored).isInstanceOf(Map.class);
        assertThat(restoredJson).isEqualTo(json);
    }

    @Test
    void readsLegacyJsonEnvelopeWithoutJavaTypeHeaders() {
        MessageProperties properties = new MessageProperties();
        properties.setContentType("application/json");
        byte[] body = "{\"schemaVersion\":1,\"eventId\":\"event-legacy\",\"payload\":{\"orderId\":\"9007199254740993\"}}"
                .getBytes(StandardCharsets.UTF_8);

        Object restored = converter.fromMessage(new Message(body, properties));
        JsonNode json = mapper.valueToTree(restored);

        assertThat(restored).isInstanceOf(Map.class);
        assertThat(json.get("eventId").stringValue()).isEqualTo("event-legacy");
        assertThat(json.at("/payload/orderId").stringValue()).isEqualTo("9007199254740993");
    }
}
