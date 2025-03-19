package com.real.infrastructure.RabbitMQ;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.CustomExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "rabbitmq.enabled", havingValue = "true")
public class RabbitMQConfig {
    // 延迟交换机
    @Bean
    public CustomExchange delayExchange() {
        Map<String, Object> args = new HashMap<>();
        args.put("x-delayed-type", "direct");
        return new CustomExchange(
                "order.delay.exchange",
                "x-delayed-message", // 使用插件类型
                true,  // 持久化
                false,
                args
        );
    }

    // 延迟队列
    @Bean
    public Queue delayQueue() {
        return new Queue("order.delay.queue", true); // 持久化
    }

    // 绑定交换机和队列
    @Bean
    public Binding binding() {
        return BindingBuilder.bind(delayQueue())
                .to(delayExchange())
                .with("order.delay.routingKey")
                .noargs();
    }
}
