package com.real.domain.payment;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;
import org.springframework.stereotype.Component;

@Component
public class MockPaymentProvider implements PaymentProvider {
    private final byte[] key;

    public MockPaymentProvider(MockPaymentProperties properties) {
        this.key = properties.getSecret().getBytes(StandardCharsets.UTF_8);
    }

    @Override public String provider() { return "MOCK"; }

    @Override
    public String sign(String timestamp, String nonce, byte[] rawBody) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            mac.update(timestamp.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '\n');
            mac.update(nonce.getBytes(StandardCharsets.US_ASCII));
            mac.update((byte) '\n');
            mac.update(rawBody);
            return HexFormat.of().formatHex(mac.doFinal());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }

    @Override
    public boolean verify(String timestamp, String nonce, byte[] rawBody, String suppliedSignature) {
        if (suppliedSignature == null || !suppliedSignature.matches("^[0-9a-f]{64}$")) return false;
        return MessageDigest.isEqual(
                sign(timestamp, nonce, rawBody).getBytes(StandardCharsets.US_ASCII),
                suppliedSignature.getBytes(StandardCharsets.US_ASCII));
    }
}
