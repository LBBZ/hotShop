package com.real.task.seckill;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties(prefix = "hotshop.seckill.order-consumer")
public class SeckillOrderProperties {
    private boolean enabled = true;
    private String groupName = "hotshop-order-v1";
    private String consumerPrefix = "order";
    private int readBatch = 20;
    private Duration readBlock = Duration.ofSeconds(2);
    private Duration discoveryInterval = Duration.ofSeconds(10);
    private Duration claimIdle = Duration.ofSeconds(30);
    private int claimBatch = 20;
    private Duration retryInitialBackoff = Duration.ofSeconds(1);
    private Duration retryMaxBackoff = Duration.ofMinutes(5);
    private double retryMultiplier = 2.0d;
    private int deterministicFailureAttempts = 3;
    private Duration orderTimeout = Duration.ofMinutes(15);
    private Duration paymentTimeout = Duration.ofMinutes(15);
    private Duration reconciliationInterval = Duration.ofMinutes(5);
    private int reconciliationBatch = 100;
    private boolean reconciliationDryRun = true;
    private boolean autoRepair = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getGroupName() {
        return groupName;
    }

    public void setGroupName(String groupName) {
        this.groupName = groupName;
    }

    public String getConsumerPrefix() {
        return consumerPrefix;
    }

    public void setConsumerPrefix(String consumerPrefix) {
        this.consumerPrefix = consumerPrefix;
    }

    public int getReadBatch() {
        return readBatch;
    }

    public void setReadBatch(int readBatch) {
        this.readBatch = positive(readBatch, "readBatch");
    }

    public Duration getReadBlock() {
        return readBlock;
    }

    public void setReadBlock(Duration readBlock) {
        this.readBlock = positive(readBlock, "readBlock");
    }

    public Duration getDiscoveryInterval() {
        return discoveryInterval;
    }

    public void setDiscoveryInterval(Duration discoveryInterval) {
        this.discoveryInterval = positive(discoveryInterval, "discoveryInterval");
    }

    public Duration getClaimIdle() {
        return claimIdle;
    }

    public void setClaimIdle(Duration claimIdle) {
        this.claimIdle = positive(claimIdle, "claimIdle");
    }

    public int getClaimBatch() {
        return claimBatch;
    }

    public void setClaimBatch(int claimBatch) {
        this.claimBatch = positive(claimBatch, "claimBatch");
    }

    public Duration getRetryInitialBackoff() {
        return retryInitialBackoff;
    }

    public void setRetryInitialBackoff(Duration retryInitialBackoff) {
        this.retryInitialBackoff = positive(retryInitialBackoff, "retryInitialBackoff");
    }

    public Duration getRetryMaxBackoff() {
        return retryMaxBackoff;
    }

    public void setRetryMaxBackoff(Duration retryMaxBackoff) {
        this.retryMaxBackoff = positive(retryMaxBackoff, "retryMaxBackoff");
    }

    public double getRetryMultiplier() {
        return retryMultiplier;
    }

    public void setRetryMultiplier(double retryMultiplier) {
        if (!Double.isFinite(retryMultiplier) || retryMultiplier < 1.0d) {
            throw new IllegalArgumentException("retryMultiplier must be at least 1");
        }
        this.retryMultiplier = retryMultiplier;
    }

    public int getDeterministicFailureAttempts() {
        return deterministicFailureAttempts;
    }

    public void setDeterministicFailureAttempts(int deterministicFailureAttempts) {
        this.deterministicFailureAttempts = positive(
                deterministicFailureAttempts,
                "deterministicFailureAttempts"
        );
    }

    public Duration getOrderTimeout() {
        return orderTimeout;
    }

    public void setOrderTimeout(Duration orderTimeout) {
        this.orderTimeout = positive(orderTimeout, "orderTimeout");
    }

    public Duration getPaymentTimeout() {
        return paymentTimeout;
    }

    public void setPaymentTimeout(Duration paymentTimeout) {
        this.paymentTimeout = positive(paymentTimeout, "paymentTimeout");
    }

    public Duration getReconciliationInterval() {
        return reconciliationInterval;
    }

    public void setReconciliationInterval(Duration reconciliationInterval) {
        this.reconciliationInterval = positive(reconciliationInterval, "reconciliationInterval");
    }

    public int getReconciliationBatch() {
        return reconciliationBatch;
    }

    public void setReconciliationBatch(int reconciliationBatch) {
        this.reconciliationBatch = positive(reconciliationBatch, "reconciliationBatch");
    }

    public boolean isReconciliationDryRun() {
        return reconciliationDryRun;
    }

    public void setReconciliationDryRun(boolean reconciliationDryRun) {
        this.reconciliationDryRun = reconciliationDryRun;
    }

    public boolean isAutoRepair() {
        return autoRepair;
    }

    public void setAutoRepair(boolean autoRepair) {
        this.autoRepair = autoRepair;
    }

    private static int positive(int value, String name) {
        if (value <= 0) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    private static Duration positive(Duration value, String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }
}
