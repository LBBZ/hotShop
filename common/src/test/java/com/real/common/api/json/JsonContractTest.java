package com.real.common.api.json;

import com.real.common.api.dto.AdminStockAdjustmentRequest;
import com.real.common.api.dto.CreateOrderItemRequest;
import com.real.common.api.dto.ProductResponse;
import com.real.common.api.dto.ProductWriteRequest;
import com.real.common.api.dto.PurchaseDraftItemRequest;
import com.real.common.api.dto.PurchaseDraftItemResponse;
import com.real.common.api.dto.PurchaseDraftResponse;
import jakarta.validation.Validation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JsonContractTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @ParameterizedTest
    @ValueSource(strings = {"1", "9007199254740993", "9223372036854775807"})
    void readsLongIdsWithoutLosingPrecision(String id) {
        CreateOrderItemRequest item = mapper.readValue(
                "{\"productId\":\"" + id + "\",\"quantity\":1}", CreateOrderItemRequest.class);

        assertThat(item.productId()).isEqualTo(Long.valueOf(id));
    }

    @ParameterizedTest
    @ValueSource(strings = {"1", "1.0", "true", "[]", "{}", "\"\"", "\"0\"", "\"01\"",
            "\"-1\"", "\"+1\"", "\" 1\"", "\"1e3\"", "\"9223372036854775808\"",
            "\"10000000000000000000\""})
    void rejectsNonCanonicalAndOutOfRangeIds(String value) {
        assertThatThrownBy(() -> mapper.readValue(
                "{\"productId\":" + value + ",\"quantity\":1}", CreateOrderItemRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.00", "1.20", "99999999999999999.99"})
    void readsMoneyAsExactDecimalStrings(String price) {
        ProductWriteRequest request = mapper.readValue(productJson("\"" + price + "\""),
                ProductWriteRequest.class);

        assertThat(request.price()).isEqualTo(new BigDecimal(price));
        assertThat(request.price().scale()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"1.20", "true", "[]", "{}", "\"1\"", "\"1.2\"", "\"1.200\"",
            "\"01.20\"", "\"-1.20\"", "\"+1.20\"", "\" 1.20\"", "\"1e2\""})
    void rejectsMoneyCoercionAndNonCanonicalScale(String price) {
        assertThatThrownBy(() -> mapper.readValue(productJson(price), ProductWriteRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {Integer.MIN_VALUE, -1, 0, 1, Integer.MAX_VALUE})
    void acceptsAllSignedIntegerBoundaries(int delta) {
        AdminStockAdjustmentRequest request = mapper.readValue(stockJson(String.valueOf(delta)),
                AdminStockAdjustmentRequest.class);

        assertThat(request.delta()).isEqualTo(delta);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"1\"", "true", "1.0", "1.5", "1e0", "[]", "{}",
            "2147483648", "-2147483649"})
    void rejectsDeltaCoercionAndOverflow(String delta) {
        assertThatThrownBy(() -> mapper.readValue(stockJson(delta), AdminStockAdjustmentRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 100})
    void acceptsPositivePurchaseQuantities(int quantity) {
        PurchaseDraftItemRequest item = mapper.readValue(purchaseJson(String.valueOf(quantity)),
                PurchaseDraftItemRequest.class);

        assertThat(item.quantity()).isEqualTo(quantity);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "\"1\"", "true", "1.0", "1.5", "1e0", "[]", "{}",
            "2147483648", "-2147483649"})
    void rejectsPurchaseQuantityCoercionAndOverflow(String quantity) {
        assertThatThrownBy(() -> mapper.readValue(purchaseJson(quantity), PurchaseDraftItemRequest.class))
                .isInstanceOf(JacksonException.class);
    }

    @Test
    void requiredNullsAndQuantityMaximumRemainBeanValidationErrors() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertThat(validator.validate(mapper.readValue(
                    "{\"productId\":null,\"quantity\":1}", CreateOrderItemRequest.class)))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                            .isEqualTo("productId"));
            assertThat(validator.validate(mapper.readValue(productJson("null"), ProductWriteRequest.class)))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("price"));
            assertThat(validator.validate(mapper.readValue(stockJson("null"), AdminStockAdjustmentRequest.class)))
                    .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("delta"));
            for (String quantity : List.of("null", "101")) {
                assertThat(validator.validate(mapper.readValue(purchaseJson(quantity), PurchaseDraftItemRequest.class)))
                        .anySatisfy(violation -> assertThat(violation.getPropertyPath().toString())
                                .isEqualTo("quantity"));
            }
        }
    }

    @Test
    void purchaseItemsRejectUnexpectedFieldsEvenWithLenientGlobalBinding() {
        JsonMapper lenient = JsonMapper.builder()
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).build();

        assertThatThrownBy(() -> lenient.readValue(
                "{\"productId\":\"1\",\"quantity\":1,\"unitPrice\":\"0.01\"}",
                PurchaseDraftItemRequest.class)).isInstanceOf(JacksonException.class);
    }

    @Test
    void productResponseKeepsLargeIdsMoneyAndInstantsAsStrings() {
        ProductResponse product = new ProductResponse(9007199254740993L, "Product",
                new BigDecimal("12345678901234567.89"), 2, "category", null,
                Instant.parse("2026-10-01T00:00:00Z"), Long.MAX_VALUE);

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(product));

        assertThat(json.get("productId").stringValue()).isEqualTo("9007199254740993");
        assertThat(json.get("price").stringValue()).isEqualTo("12345678901234567.89");
        assertThat(json.get("stock").intValue()).isEqualTo(2);
        assertThat(json.get("createdAt").stringValue()).isEqualTo("2026-10-01T00:00:00Z");
        assertThat(json.get("version").stringValue()).isEqualTo("9223372036854775807");
        assertThat(json.has("description")).isFalse();
    }

    @Test
    void purchaseDraftResponsePreservesNestedMoneyScaleAndConfirmationFlag() {
        PurchaseDraftItemResponse item = new PurchaseDraftItemResponse(Long.MAX_VALUE, "Product", 2,
                new BigDecimal("0.10"), new BigDecimal("0.20"), "CNY");
        PurchaseDraftResponse draft = new PurchaseDraftResponse("draft-1", "PURCHASE", List.of(item),
                new BigDecimal("0.20"), "CNY", Instant.parse("2026-10-01T00:10:00Z"), true, "CONFIRM");

        JsonNode json = mapper.readTree(mapper.writeValueAsBytes(draft));

        assertThat(json.get("totalPriceSnapshot").stringValue()).isEqualTo("0.20");
        assertThat(json.at("/items/0/productId").stringValue()).isEqualTo("9223372036854775807");
        assertThat(json.at("/items/0/unitPriceSnapshot").stringValue()).isEqualTo("0.10");
        assertThat(json.at("/items/0/lineAmountSnapshot").stringValue()).isEqualTo("0.20");
        assertThat(json.at("/items/0/quantity").intValue()).isEqualTo(2);
        assertThat(json.get("confirmationRequired").booleanValue()).isTrue();
        assertThat(json.get("validUntil").stringValue()).isEqualTo("2026-10-01T00:10:00Z");
    }

    private static String productJson(String price) {
        return "{\"name\":\"Product\",\"price\":" + price + ",\"stock\":1,\"category\":\"category\"}";
    }

    private static String stockJson(String delta) {
        return "{\"delta\":" + delta + ",\"expectedVersion\":\"0\",\"reason\":\"Restock\"}";
    }

    private static String purchaseJson(String quantity) {
        return "{\"productId\":\"1\",\"quantity\":" + quantity + "}";
    }
}
