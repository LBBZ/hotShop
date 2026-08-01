package com.real.infrastructure.RabbitMQ;

import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.annotation.Value;

import java.time.Duration;

@Configuration
@ConditionalOnProperty(name = "rabbitmq.enabled", havingValue = "true")
public class RabbitMQConfig {
    public static final String BUSINESS_EXCHANGE = "hotshop.business.events.v1";
    public static final String ORDER_CREATED_QUEUE = "hotshop.order.created.v1";
    public static final String RESERVATION_COMPENSATED_QUEUE = "hotshop.reservation.compensated.v1";
    public static final String ORDER_CANCELED_QUEUE = "hotshop.order.canceled.v1";
    public static final String TIMEOUT_SCHEDULE_EXCHANGE = "hotshop.order.timeout.schedule.v1";
    public static final String TIMEOUT_DELAY_QUEUE = "hotshop.order.timeout.delay.v1";
    public static final String TIMEOUT_READY_EXCHANGE = "hotshop.order.timeout.ready.v1";
    public static final String TIMEOUT_READY_QUEUE = "hotshop.order.timeout.ready.v1";
    public static final String TIMEOUT_DEAD_EXCHANGE = "hotshop.order.timeout.dead.v1";
    public static final String TIMEOUT_DEAD_QUEUE = "hotshop.order.timeout.dead.v1";
    public static final String TIMEOUT_ROUTING_KEY = "order.timeout";
    private final int legacyTimeoutMs;

    public RabbitMQConfig(@Value("${hotshop.order.legacy-payment-timeout:15m}") Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Legacy order timeout must be a positive queue TTL");
        }
        this.legacyTimeoutMs = Math.toIntExact(timeout.toMillis());
    }

    @Bean public MessageConverter jsonMessageConverter() { return new Jackson2JsonMessageConverter(); }

    @Bean public RabbitTemplate rabbitTemplate(ConnectionFactory factory, MessageConverter converter) {
        RabbitTemplate template = new RabbitTemplate(factory);
        template.setMessageConverter(converter);
        template.setMandatory(true);
        return template;
    }

    @Bean public TopicExchange businessExchange() { return new TopicExchange(BUSINESS_EXCHANGE, true, false); }
    @Bean public Queue orderCreatedQueue() { return QueueBuilder.durable(ORDER_CREATED_QUEUE).build(); }
    @Bean public Queue reservationCompensatedQueue() { return QueueBuilder.durable(RESERVATION_COMPENSATED_QUEUE).build(); }
    @Bean public Queue orderCanceledQueue() { return QueueBuilder.durable(ORDER_CANCELED_QUEUE).build(); }
    @Bean public Binding orderCreatedBinding() { return BindingBuilder.bind(orderCreatedQueue()).to(businessExchange()).with("ORDER_CREATED"); }
    @Bean public Binding reservationCompensatedBinding() { return BindingBuilder.bind(reservationCompensatedQueue()).to(businessExchange()).with("RESERVATION_COMPENSATED"); }
    @Bean public Binding orderCanceledBinding() { return BindingBuilder.bind(orderCanceledQueue()).to(businessExchange()).with("ORDER_CANCELED"); }

    @Bean public DirectExchange timeoutScheduleExchange() { return new DirectExchange(TIMEOUT_SCHEDULE_EXCHANGE, true, false); }
    @Bean public DirectExchange timeoutReadyExchange() { return new DirectExchange(TIMEOUT_READY_EXCHANGE, true, false); }
    @Bean public DirectExchange timeoutDeadExchange() { return new DirectExchange(TIMEOUT_DEAD_EXCHANGE, true, false); }
    @Bean public Queue timeoutDelayQueue() {
        return QueueBuilder.durable(TIMEOUT_DELAY_QUEUE)
                .ttl(legacyTimeoutMs)
                .deadLetterExchange(TIMEOUT_READY_EXCHANGE)
                .deadLetterRoutingKey(TIMEOUT_ROUTING_KEY).build();
    }
    @Bean public Queue timeoutReadyQueue() {
        return QueueBuilder.durable(TIMEOUT_READY_QUEUE)
                .deadLetterExchange(TIMEOUT_DEAD_EXCHANGE)
                .deadLetterRoutingKey(TIMEOUT_ROUTING_KEY).build();
    }
    @Bean public Queue timeoutDeadQueue() { return QueueBuilder.durable(TIMEOUT_DEAD_QUEUE).build(); }
    @Bean public Binding timeoutScheduleBinding() { return BindingBuilder.bind(timeoutDelayQueue()).to(timeoutScheduleExchange()).with(TIMEOUT_ROUTING_KEY); }
    @Bean public Binding timeoutReadyBinding() { return BindingBuilder.bind(timeoutReadyQueue()).to(timeoutReadyExchange()).with(TIMEOUT_ROUTING_KEY); }
    @Bean public Binding timeoutDeadBinding() { return BindingBuilder.bind(timeoutDeadQueue()).to(timeoutDeadExchange()).with(TIMEOUT_ROUTING_KEY); }
}
