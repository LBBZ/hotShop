package com.real.portal.payment;

import org.springframework.http.HttpStatus;

public class CallbackRejectedException extends RuntimeException {
    private final String category;
    private final HttpStatus status;
    private final String paymentNo;
    private final String outcome;

    public CallbackRejectedException(String category, HttpStatus status) {
        this(category, status, null, null);
    }
    public CallbackRejectedException(String category, HttpStatus status, String paymentNo, String outcome) {
        super(category);
        this.category = category;
        this.status = status;
        this.paymentNo = paymentNo;
        this.outcome = outcome;
    }
    public String category() { return category; }
    public HttpStatus status() { return status; }
    public String paymentNo() { return paymentNo; }
    public String outcome() { return outcome; }
}
