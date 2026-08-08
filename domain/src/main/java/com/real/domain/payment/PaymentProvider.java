package com.real.domain.payment;

public interface PaymentProvider {
    String provider();

    String sign(String timestamp, String nonce, byte[] rawBody);

    boolean verify(String timestamp, String nonce, byte[] rawBody, String suppliedSignature);
}
