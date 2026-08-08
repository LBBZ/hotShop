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
    public static final String MOCK_CALLBACK_EXCHANGE = "hotshop.mock-payment.callback.v1";
    public static final String MOCK_CALLBACK_QUEUE = "hotshop.mock-payment.callback.delivery.v1";
    public static final String MOCK_CALLBACK_RETRY_EXCHANGE = "hotshop.mock-payment.callback.retry.v1";
    public static final String MOCK_CALLBACK_RETRY_QUEUE = "hotshop.mock-payment.callback.retry.v1";
    public static final String MOCK_CALLBACK_DEAD_EXCHANGE = "hotshop.mock-payment.callback.dead.v1";
    public static final String MOCK_CALLBACK_DEAD_QUEUE = "hotshop.mock-payment.callback.dead.v1";
    public static final String MOCK_CALLBACK_ROUTING_KEY = "mock-payment.callback";
    public static final String PAYMENT_SUCCEEDED_QUEUE = "hotshop.payment.succeeded.v1";
    public static final String PAYMENT_FAILED_QUEUE = "hotshop.payment.failed.v1";
    public static final String PAYMENT_LATE_SUCCEEDED_QUEUE = "hotshop.payment.late-succeeded.v1";
    public static final String SECKILL_PAYMENT_EXPIRED_QUEUE = "hotshop.seckill.payment-expired.v1";
    public static final String SECKILL_PAYMENT_EXPIRED_RETRY_EXCHANGE = "hotshop.seckill.payment-expired.retry.v1";
    public static final String SECKILL_PAYMENT_EXPIRED_RETRY_QUEUE = "hotshop.seckill.payment-expired.retry.v1";
    public static final String SECKILL_PAYMENT_EXPIRED_DEAD_EXCHANGE = "hotshop.seckill.payment-expired.dead.v1";
    public static final String SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE = "hotshop.seckill.payment-expired.dead.v1";
    public static final String SECKILL_PAYMENT_EXPIRED_ROUTING_KEY = "seckill.payment-expired";
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

    @Bean public Queue paymentSucceededQueue() { return QueueBuilder.durable(PAYMENT_SUCCEEDED_QUEUE).build(); }
    @Bean public Queue paymentFailedQueue() { return QueueBuilder.durable(PAYMENT_FAILED_QUEUE).build(); }
    @Bean public Queue paymentLateSucceededQueue() { return QueueBuilder.durable(PAYMENT_LATE_SUCCEEDED_QUEUE).build(); }
    @Bean public Queue seckillPaymentExpiredQueue() {
        return QueueBuilder.durable(SECKILL_PAYMENT_EXPIRED_QUEUE)
                .deadLetterExchange(SECKILL_PAYMENT_EXPIRED_DEAD_EXCHANGE)
                .deadLetterRoutingKey(SECKILL_PAYMENT_EXPIRED_ROUTING_KEY).build();
    }
    @Bean public Binding paymentSucceededBinding() { return BindingBuilder.bind(paymentSucceededQueue()).to(businessExchange()).with("PAYMENT_SUCCEEDED"); }
    @Bean public Binding paymentFailedBinding() { return BindingBuilder.bind(paymentFailedQueue()).to(businessExchange()).with("PAYMENT_FAILED"); }
    @Bean public Binding paymentLateSucceededBinding() { return BindingBuilder.bind(paymentLateSucceededQueue()).to(businessExchange()).with("PAYMENT_LATE_SUCCEEDED"); }
    @Bean public Binding seckillPaymentExpiredBinding() { return BindingBuilder.bind(seckillPaymentExpiredQueue()).to(businessExchange()).with("SECKILL_PAYMENT_EXPIRED"); }

    @Bean public DirectExchange seckillPaymentExpiredRetryExchange() {
        return new DirectExchange(SECKILL_PAYMENT_EXPIRED_RETRY_EXCHANGE, true, false);
    }
    @Bean public DirectExchange seckillPaymentExpiredDeadExchange() {
        return new DirectExchange(SECKILL_PAYMENT_EXPIRED_DEAD_EXCHANGE, true, false);
    }
    @Bean public Queue seckillPaymentExpiredRetryQueue() {
        return QueueBuilder.durable(SECKILL_PAYMENT_EXPIRED_RETRY_QUEUE)
                .deadLetterExchange(BUSINESS_EXCHANGE)
                .deadLetterRoutingKey("SECKILL_PAYMENT_EXPIRED").build();
    }
    @Bean public Queue seckillPaymentExpiredDeadQueue() {
        return QueueBuilder.durable(SECKILL_PAYMENT_EXPIRED_DEAD_QUEUE).build();
    }
    @Bean public Binding seckillPaymentExpiredRetryBinding() {
        return BindingBuilder.bind(seckillPaymentExpiredRetryQueue())
                .to(seckillPaymentExpiredRetryExchange()).with(SECKILL_PAYMENT_EXPIRED_ROUTING_KEY);
    }
    @Bean public Binding seckillPaymentExpiredDeadBinding() {
        return BindingBuilder.bind(seckillPaymentExpiredDeadQueue())
                .to(seckillPaymentExpiredDeadExchange()).with(SECKILL_PAYMENT_EXPIRED_ROUTING_KEY);
    }

    @Bean public DirectExchange mockCallbackExchange() { return new DirectExchange(MOCK_CALLBACK_EXCHANGE, true, false); }
    @Bean public DirectExchange mockCallbackRetryExchange() { return new DirectExchange(MOCK_CALLBACK_RETRY_EXCHANGE, true, false); }
    @Bean public DirectExchange mockCallbackDeadExchange() { return new DirectExchange(MOCK_CALLBACK_DEAD_EXCHANGE, true, false); }
    @Bean public Queue mockCallbackQueue() {
        return QueueBuilder.durable(MOCK_CALLBACK_QUEUE)
                .deadLetterExchange(MOCK_CALLBACK_DEAD_EXCHANGE)
                .deadLetterRoutingKey(MOCK_CALLBACK_ROUTING_KEY).build();
    }
    @Bean public Queue mockCallbackRetryQueue() {
        return QueueBuilder.durable(MOCK_CALLBACK_RETRY_QUEUE)
                .deadLetterExchange(MOCK_CALLBACK_EXCHANGE)
                .deadLetterRoutingKey(MOCK_CALLBACK_ROUTING_KEY).build();
    }
    @Bean public Queue mockCallbackDeadQueue() { return QueueBuilder.durable(MOCK_CALLBACK_DEAD_QUEUE).build(); }
    @Bean public Binding mockCallbackBinding() { return BindingBuilder.bind(mockCallbackQueue()).to(mockCallbackExchange()).with(MOCK_CALLBACK_ROUTING_KEY); }
    @Bean public Binding mockCallbackRetryBinding() { return BindingBuilder.bind(mockCallbackRetryQueue()).to(mockCallbackRetryExchange()).with(MOCK_CALLBACK_ROUTING_KEY); }
    @Bean public Binding mockCallbackDeadBinding() { return BindingBuilder.bind(mockCallbackDeadQueue()).to(mockCallbackDeadExchange()).with(MOCK_CALLBACK_ROUTING_KEY); }
}
