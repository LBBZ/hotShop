package com.real.task.test;

import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
public class RabbitMQConnectionTest {

    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Test
    public void testSendMessage() {
        String queueName = "testQueue";
        String message = "Hello, RabbitMQ!";
        rabbitTemplate.convertAndSend(queueName, message);
        System.out.println("Message sent: " + message);
    }
}
