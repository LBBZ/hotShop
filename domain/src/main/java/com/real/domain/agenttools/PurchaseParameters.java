package com.real.domain.agenttools;

import com.real.common.api.ApiException;
import com.real.common.api.dto.PurchaseDraftItemRequest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

public final class PurchaseParameters {
    public static final String ACTION = "CREATE_ORDER";

    private PurchaseParameters() { }

    public static List<Item> normalize(List<PurchaseDraftItemRequest> requested) {
        if (requested == null || requested.isEmpty() || requested.size() > 20) {
            throw ApiException.badRequest("PURCHASE_ITEMS_INVALID", "Purchase items are invalid");
        }
        Set<Long> unique = new HashSet<>();
        List<Item> items = requested.stream().map(item -> {
            final long productId;
            try {
                productId = Long.parseLong(item.productId());
            } catch (NumberFormatException exception) {
                throw ApiException.badRequest("PRODUCT_ID_INVALID", "Product ID is invalid");
            }
            if (productId <= 0 || item.quantity() == null || item.quantity() <= 0
                    || item.quantity() > 100 || !unique.add(productId)) {
                throw ApiException.badRequest(
                        "PURCHASE_ITEMS_INVALID",
                        "Purchase items must contain unique products and valid quantities"
                );
            }
            return new Item(productId, item.quantity());
        }).sorted(Comparator.comparingLong(Item::productId)).toList();
        return List.copyOf(items);
    }

    public static String canonical(List<Item> items) {
        return ACTION + "|" + items.stream()
                .map(item -> item.productId() + ":" + item.quantity())
                .reduce((left, right) -> left + "," + right)
                .orElseThrow();
    }

    public static String digest(List<Item> items) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonical(items).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public record Item(long productId, int quantity) { }
}
