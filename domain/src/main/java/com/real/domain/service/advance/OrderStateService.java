package com.real.domain.service.advance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.real.common.enums.OrderStatus;
import com.real.common.exception.InventoryShortageException;
import com.real.domain.entity.Order;
import com.real.domain.entity.Product;
import com.real.domain.mapper.OrderMapper;
import com.real.domain.mapper.ProductMapper;
import com.real.domain.messaging.OutboxMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OrderStateService {
    private final OrderMapper orders;
    private final ProductMapper products;
    private final OutboxMapper outbox;
    private final ObjectMapper json;
    private final Duration legacyTimeout;

    public OrderStateService(OrderMapper orders, ProductMapper products, OutboxMapper outbox, ObjectMapper json,
            @Value("${hotshop.order.legacy-payment-timeout:15m}") Duration legacyTimeout) {
        if (legacyTimeout == null || legacyTimeout.isZero() || legacyTimeout.isNegative()
                || legacyTimeout.toMillis() > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Legacy order timeout must be a positive duration");
        }
        this.orders = orders;
        this.products = products;
        this.outbox = outbox;
        this.json = json;
        this.legacyTimeout = legacyTimeout;
    }

    @Transactional public void changeOrderStatus(Order order, OrderStatus status) {
        if(!order.getStatus().canTransitionTo(status)) throw new IllegalStateException("Illegal order transition");
        order.setStatus(status); orders.updateOrder(order); if(status==OrderStatus.CANCELED) releaseStock(order);
    }

    @Transactional
    public boolean tryCreateOrder(Order order) {
        persistOrdinaryOrder(order);
        return true;
    }

    @Transactional
    public String createOrder(Order order) {
        persistOrdinaryOrder(order);
        return order.getOrderId();
    }

    private void persistOrdinaryOrder(Order order) {
        if (order.getOrderId() == null) {
            order.setOrderId(UUID.randomUUID().toString());
            order.setStatus(OrderStatus.PENDING);
        }
        for (var item : order.getItems()) {
            if (products.reduceStock(item.getProductId(), item.getQuantity()) == 0) {
                throw new InventoryShortageException(
                        "Insufficient inventory for product " + item.getProductId());
            }
        }
        BigDecimal total = BigDecimal.ZERO;
        for (var item : order.getItems()) {
            Product product = products.selectById(item.getProductId());
            item.setPrice(product.getPrice());
            total = total.add(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        order.setTotalAmount(total);
        order.setExpiresAt(LocalDateTime.now(ZoneOffset.UTC).plus(legacyTimeout));
        orders.insertOrder(order);
        for (var item : order.getItems()) {
            item.setOrderId(order.getOrderId());
            orders.insertOrderItem(item);
        }
        writeEvents(order);
    }

    @Transactional public void payOrder(String id) { transition(id,OrderStatus.PAID,false); }
    @Transactional public void cancelOrder(String id) { transition(id,OrderStatus.CANCELED,true); }
    @Transactional public void completeOrder(String id) { transition(id,OrderStatus.COMPLETED,false); }

    private void transition(String id,OrderStatus target,boolean restore) {
        Order order=orders.selectOrderById(id);
        if(order==null||!order.getStatus().canTransitionTo(target)) throw new IllegalStateException("Illegal order transition");
        order.setStatus(target); orders.updateOrder(order); if(restore) releaseStock(order);
    }
    private void releaseStock(Order order) { order.getItems().forEach(i->products.increaseStock(i.getProductId(),i.getQuantity())); }
    private void writeEvents(Order order) {
        Map<String,Object> p=new LinkedHashMap<>(); p.put("schemaVersion",1); p.put("orderId",order.getOrderId());
        p.put("userId",order.getUserId()); p.put("amount",order.getTotalAmount().toPlainString()); p.put("currency","CNY");
        p.put("expiresAtMs",order.getExpiresAt().toInstant(ZoneOffset.UTC).toEpochMilli());
        try { String body=json.writeValueAsString(p);
            outbox.insert(eventId("ORDER_CREATED",order.getOrderId()),"ORDER",order.getOrderId(),"ORDER_CREATED",body);
            outbox.insert(eventId("LEGACY_ORDER_TIMEOUT_REQUESTED",order.getOrderId()),"ORDER",order.getOrderId(),"LEGACY_ORDER_TIMEOUT_REQUESTED",body);
        } catch(JsonProcessingException e) { throw new IllegalStateException("Cannot serialize order event",e); }
    }
    static String eventId(String type,String id) { return UUID.nameUUIDFromBytes(("hotshop/outbox/v1/"+type+"/"+id).getBytes(StandardCharsets.UTF_8)).toString(); }
}
