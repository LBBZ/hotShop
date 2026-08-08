package com.real.domain.payment;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;

class MockPaymentProviderTest {
    @Test
    void enabledProviderRejectsSecretsShorterThanThirtyTwoBytes() {
        MockPaymentProperties properties = new MockPaymentProperties();
        properties.setEnabled(true);
        properties.setSecret("short");
        assertThatThrownBy(properties::validate).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("32");
    }

    @Test
    void hmacCoversTimestampNonceAndEveryRawBodyByte() {
        MockPaymentProperties properties = new MockPaymentProperties();
        properties.setEnabled(true);
        properties.setSecret(UUID.randomUUID().toString());
        properties.validate();
        MockPaymentProvider provider = new MockPaymentProvider(properties);
        byte[] body = "{\"outcome\":\"SUCCEEDED\"}".getBytes(StandardCharsets.UTF_8);
        String signature = provider.sign("1785542400", "abcdefghijklmnop", body);
        assertThat(provider.verify("1785542400", "abcdefghijklmnop", body, signature)).isTrue();
        byte[] changed = body.clone(); changed[5] ^= 1;
        assertThat(provider.verify("1785542400", "abcdefghijklmnop", changed, signature)).isFalse();
        assertThat(provider.verify("1785542401", "abcdefghijklmnop", body, signature)).isFalse();
        assertThat(provider.verify("1785542400", "abcdefghijklmnopq", body, signature)).isFalse();
    }
}
