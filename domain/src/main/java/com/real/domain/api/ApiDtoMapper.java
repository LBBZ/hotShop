package com.real.domain.api;

import com.real.common.api.dto.CreateOrderRequest;
import com.real.common.api.dto.OrderItemResponse;
import com.real.common.api.dto.OrderResponse;
import com.real.common.api.dto.ProductResponse;
import com.real.common.api.dto.ProductWriteRequest;
import com.real.common.api.dto.UserResponse;
import com.real.domain.entity.Order;
import com.real.domain.entity.OrderItem;
import com.real.domain.entity.Product;
import com.real.domain.entity.User;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;

public final class ApiDtoMapper {
    private ApiDtoMapper() {
    }

    public static ProductResponse toProductResponse(Product product) {
        return new ProductResponse(
                product.getProductId(),
                product.getName(),
                money(product.getPrice()),
                product.getStock(),
                product.getCategory(),
                product.getDescription(),
                toInstant(product.getCreatedAt())
        );
    }

    public static Product toProduct(ProductWriteRequest request) {
        Product product = new Product();
        product.setName(request.name());
        product.setPrice(request.price());
        product.setStock(request.stock());
        product.setCategory(request.category());
        product.setDescription(request.description());
        return product;
    }

    public static UserResponse toUserResponse(User user) {
        return new UserResponse(
                user.getUserId(),
                user.getUsername(),
                user.getEmail(),
                user.getRole(),
                toInstant(user.getCreatedAt())
        );
    }

    public static OrderResponse toOrderResponse(Order order) {
        List<OrderItemResponse> items = order.getItems() == null
                ? List.of()
                : order.getItems().stream().map(ApiDtoMapper::toOrderItemResponse).toList();
        return new OrderResponse(
                order.getOrderId(),
                order.getUserId(),
                money(order.getTotalAmount()),
                "CNY",
                order.getStatus(),
                toInstant(order.getCreatedAt()),
                items
        );
    }

    public static Order toOrder(CreateOrderRequest request) {
        Order order = new Order();
        order.setItems(request.items().stream().map(item -> {
            OrderItem entity = new OrderItem();
            entity.setProductId(item.productId());
            entity.setQuantity(item.quantity());
            return entity;
        }).toList());
        return order;
    }

    public static LocalDateTime toUtcLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static OrderItemResponse toOrderItemResponse(OrderItem item) {
        BigDecimal lineAmount = item.getPrice() == null || item.getQuantity() == null
                ? null
                : money(item.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        return new OrderItemResponse(
                item.getOrderItemId(),
                item.getProductId(),
                item.getQuantity(),
                money(item.getPrice()),
                lineAmount
        );
    }

    private static BigDecimal money(BigDecimal value) {
        return value == null ? null : value.setScale(2, RoundingMode.UNNECESSARY);
    }

    private static Instant toInstant(LocalDateTime value) {
        return value == null ? null : value.toInstant(ZoneOffset.UTC);
    }
}
