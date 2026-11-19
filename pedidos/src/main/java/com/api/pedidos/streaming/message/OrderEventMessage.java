package com.api.pedidos.streaming.message;

import com.api.pedidos.event.OrderConfirmedEvent;
import com.api.pedidos.event.OrderStatusChangedEvent;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/*
 * EP5 - DTO publicado en el topico pedidos360.pedidos.eventos (contrato del stream de pedidos).
 * Un unico sobre para todo el ciclo de vida del pedido; eventType indica que ocurrio.
 * La key del mensaje es orderId: todos los eventos de un pedido caen en la misma particion
 * y se consumen en orden (CONFIRMED -> SHIPPED -> DELIVERED).
 */
public record OrderEventMessage(
        int schemaVersion,
        UUID eventId,
        OrderEventType eventType,
        UUID orderId,
        UUID customerId,
        String previousStatus,
        String newStatus,
        BigDecimal totalAmount,
        List<Item> items,
        String source,
        OffsetDateTime occurredAt) {

    public static final int SCHEMA_VERSION = 1;

    public enum OrderEventType {
        ORDER_CONFIRMED,
        ORDER_STATUS_CHANGED,
        ORDER_CANCELLED
    }

    public record Item(UUID productId, String sku, int quantity) {
    }

    public static OrderEventMessage confirmed(OrderConfirmedEvent event) {
        List<Item> items = event.items().stream()
                .map(item -> new Item(item.productId(), item.sku(), item.quantity()))
                .toList();
        return new OrderEventMessage(SCHEMA_VERSION, event.eventId(), OrderEventType.ORDER_CONFIRMED,
                event.orderId(), event.customerId(), null, "CONFIRMED", event.totalAmount(), items,
                "API", event.occurredAt());
    }

    public static OrderEventMessage statusChanged(OrderStatusChangedEvent event) {
        OrderEventType type = event.isCancellation()
                ? OrderEventType.ORDER_CANCELLED
                : OrderEventType.ORDER_STATUS_CHANGED;
        return new OrderEventMessage(SCHEMA_VERSION, event.eventId(), type,
                event.orderId(), event.customerId(),
                event.previousStatus() == null ? null : event.previousStatus().name(),
                event.newStatus().name(), null, List.of(), event.source(), event.occurredAt());
    }
}
