package com.real.task.payment;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "hotshop.seckill.payment-expired-delivery")
public class SeckillPaymentExpiredDeliveryProperties {
    private Duration retryDelay = Duration.ofSeconds(2);
    private Duration confirmTimeout = Duration.ofSeconds(3);
    private int maxDeliveryAttempts = 5;

    @PostConstruct
    void validate() {
        if (retryDelay == null || retryDelay.isZero() || retryDelay.isNegative()
                || retryDelay.compareTo(Duration.ofHours(1)) > 0
                || confirmTimeout == null || confirmTimeout.isZero() || confirmTimeout.isNegative()
                || confirmTimeout.compareTo(Duration.ofSeconds(30)) > 0
                || maxDeliveryAttempts < 1 || maxDeliveryAttempts > 20) {
            throw new IllegalStateException("Seckill payment-expired delivery configuration is invalid");
        }
    }

    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration retryDelay) { this.retryDelay = retryDelay; }
    public Duration getConfirmTimeout() { return confirmTimeout; }
    public void setConfirmTimeout(Duration confirmTimeout) { this.confirmTimeout = confirmTimeout; }
    public int getMaxDeliveryAttempts() { return maxDeliveryAttempts; }
    public void setMaxDeliveryAttempts(int maxDeliveryAttempts) { this.maxDeliveryAttempts = maxDeliveryAttempts; }
}
