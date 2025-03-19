package com.real.domain.infra;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

// RabbitMQService.java
@Service
public class RabbitMQService {
    private final RabbitTemplate rabbitTemplate;

    @Autowired
    public RabbitMQService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    /**
     * 发送延迟消息
     * @param exchange 交换机名称
     * @param routingKey 路由键
     * @param message 消息内容
     * @param delayMillis 延迟时间（毫秒）
     */
    public void sendDelayedMessage(String exchange, String routingKey, Object message, long delayMillis) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message, msg -> {
            if (delayMillis > 0) {
                msg.getMessageProperties().setDelayLong(delayMillis);
            }
            return msg;
        });
    }

    /**
     * 发送订单超时消息（专用方法）
     * @param orderId 订单ID
     * @param timeoutMinutes 超时时间（分钟）
     */
    public void sendOrderTimeoutMessage(String orderId, int timeoutMinutes) {
        sendDelayedMessage(
                "order.delay.exchange",
                "order.delay.routingKey",
                orderId,
                timeoutMinutes * 60 * 1000L
        );
    }
}
