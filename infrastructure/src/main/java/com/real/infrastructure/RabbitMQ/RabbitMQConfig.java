package com.real.infrastructure.RabbitMQ;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(name = "rabbitmq.enabled", havingValue = "true")
public class RabbitMQConfig {
    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter());
        return template;
    }
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
        return QueueBuilder.durable("order.delay.queue")
                .withArgument("x-max-length", 10000) // 最大消息数
                .build();
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
