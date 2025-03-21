package com.real.domain.infra;

import jakarta.annotation.PostConstruct;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "rabbitmq.enabled", havingValue = "true")
public class RabbitMQService implements RabbitTemplate.ConfirmCallback {
    private final RabbitTemplate rabbitTemplate;
    @Autowired
    public RabbitMQService(RabbitTemplate rabbitTemplate) {
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void init() {
        rabbitTemplate.setConfirmCallback(this);
    }

    //  todo  test
    @Override
    public void confirm(CorrelationData correlationData, boolean ack, String cause) {
        if (!ack) {
            System.out.println("CorrelationData: " + correlationData);
            // 可添加重试逻辑或记录到数据库
        }
    }

    /**
     * 发送延迟消息
     * @param exchange 交换机名称
     * @param routingKey 路由键
     * @param message 消息内容
     * @param delayMillis 延迟时间（毫秒）
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000))
    public void sendDelayedMessage(String exchange, String routingKey, Object message, long delayMillis) {
        rabbitTemplate.convertAndSend(exchange, routingKey, message, msg -> {
            msg.getMessageProperties().setDeliveryMode(MessageDeliveryMode.PERSISTENT);
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
    public void sendOrderTimeoutMessage(String orderId, long timeoutMinutes) {
        sendDelayedMessage(
                "order.delay.exchange",
                "order.delay.routingKey",
                orderId,
                timeoutMinutes * 30 * 1000L
        );
    }
}
