package com.real.domain;

import com.real.domain.infra.RabbitMQService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class RabbitMQServiceCharacterizationTest {

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Test
    void initializationCurrentlyRegistersTheServiceAsConfirmCallback() {
        RabbitMQService service = new RabbitMQService(rabbitTemplate);

        service.init();

        verify(rabbitTemplate).setConfirmCallback(service);
    }

    @Test
    @DisplayName("Current known defect (TASK-09): each configured timeout minute becomes 30 seconds")
    void currentTimeoutConversionUsesThirtySecondsPerConfiguredMinute() {
        RabbitMQService service = new RabbitMQService(rabbitTemplate);
        ArgumentCaptor<MessagePostProcessor> processor =
                ArgumentCaptor.forClass(MessagePostProcessor.class);

        service.sendOrderTimeoutMessage("order-1", 2);

        verify(rabbitTemplate).convertAndSend(
                eq("order.delay.exchange"),
                eq("order.delay.routingKey"),
                eq("order-1"),
                processor.capture()
        );

        MessageProperties properties = new MessageProperties();
        Message processed = processor.getValue()
                .postProcessMessage(new Message(new byte[0], properties));

        assertEquals(60_000L, processed.getMessageProperties().getDelayLong());
        assertEquals(
                MessageDeliveryMode.PERSISTENT,
                processed.getMessageProperties().getDeliveryMode()
        );
    }
}
