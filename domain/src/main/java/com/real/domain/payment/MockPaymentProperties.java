package com.real.domain.payment;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

@Component
@ConfigurationProperties(prefix = "hotshop.mock-payment")
public class MockPaymentProperties {
    private boolean enabled;
    private String secret = "";
    private Duration allowedClockSkew = Duration.ofMinutes(5);
    private URI callbackUrl = URI.create("http://portal-service:8080/provider-callbacks/v1/mock-payment");
    private Duration httpTimeout = Duration.ofSeconds(3);
    private Duration retryDelay = Duration.ofSeconds(2);
    private int maxDeliveryAttempts = 5;
    private Duration maxSimulationDelay = Duration.ofHours(1);
    private int maxDuplicateCount = 10;
    private int maxCallbackBodyBytes = 4096;

    @PostConstruct
    void validate() {
        if (!enabled) return;
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalStateException("HOTSHOP_MOCK_PAYMENT_SECRET must contain at least 32 UTF-8 bytes");
        }
        if (allowedClockSkew == null || allowedClockSkew.isNegative() || allowedClockSkew.isZero()
                || callbackUrl == null || !"http".equalsIgnoreCase(callbackUrl.getScheme())
                || httpTimeout == null || httpTimeout.isNegative() || httpTimeout.isZero()
                || retryDelay == null || retryDelay.isNegative() || retryDelay.isZero()
                || maxDeliveryAttempts < 1 || maxDeliveryAttempts > 20
                || maxSimulationDelay == null || maxSimulationDelay.isNegative()
                || maxSimulationDelay.compareTo(Duration.ofHours(24)) > 0
                || maxDuplicateCount < 1 || maxDuplicateCount > 20
                || maxCallbackBodyBytes < 512 || maxCallbackBodyBytes > 16384) {
            throw new IllegalStateException("Mock Payment configuration is invalid");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public String getSecret() { return secret; }
    public void setSecret(String secret) { this.secret = secret; }
    public Duration getAllowedClockSkew() { return allowedClockSkew; }
    public void setAllowedClockSkew(Duration value) { this.allowedClockSkew = value; }
    public URI getCallbackUrl() { return callbackUrl; }
    public void setCallbackUrl(URI callbackUrl) { this.callbackUrl = callbackUrl; }
    public Duration getHttpTimeout() { return httpTimeout; }
    public void setHttpTimeout(Duration value) { this.httpTimeout = value; }
    public Duration getRetryDelay() { return retryDelay; }
    public void setRetryDelay(Duration value) { this.retryDelay = value; }
    public int getMaxDeliveryAttempts() { return maxDeliveryAttempts; }
    public void setMaxDeliveryAttempts(int value) { this.maxDeliveryAttempts = value; }
    public Duration getMaxSimulationDelay() { return maxSimulationDelay; }
    public void setMaxSimulationDelay(Duration value) { this.maxSimulationDelay = value; }
    public int getMaxDuplicateCount() { return maxDuplicateCount; }
    public void setMaxDuplicateCount(int value) { this.maxDuplicateCount = value; }
    public int getMaxCallbackBodyBytes() { return maxCallbackBodyBytes; }
    public void setMaxCallbackBodyBytes(int value) { this.maxCallbackBodyBytes = value; }
}
