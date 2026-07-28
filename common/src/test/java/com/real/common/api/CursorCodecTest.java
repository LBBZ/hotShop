package com.real.common.api;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CursorCodecTest {
    @Test
    void roundTripsScopedStableCursors() {
        LocalDateTime time = LocalDateTime.of(2026, 7, 27, 12, 30, 15, 123_000_000);

        String productCursor = CursorCodec.encodeLong("products", 42L);
        String orderCursor = CursorCodec.encodeTimeAndString("user-orders", time, "order_42");

        assertThat(CursorCodec.decodeLong(productCursor, "products").id()).isEqualTo(42L);
        assertThat(CursorCodec.decodeTimeAndString(orderCursor, "user-orders"))
                .isEqualTo(new CursorCodec.TimeStringCursor(time, "order_42"));
    }

    @Test
    void rejectsMalformedAndCrossListCursorsWithoutLeakingPayload() {
        String cursor = CursorCodec.encodeLong("products", 42L);

        assertThatThrownBy(() -> CursorCodec.decodeLong(cursor, "admin-users"))
                .isInstanceOf(ApiException.class)
                .extracting("code")
                .isEqualTo("CURSOR_INVALID");
        assertThatThrownBy(() -> CursorCodec.decodeLong("not-base64!", "products"))
                .isInstanceOf(ApiException.class)
                .hasMessage("The cursor is invalid or belongs to another list");
    }
}
