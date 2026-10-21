package com.api.pedidos.event;

import com.api.pedidos.model.OrderStatus;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * Evento de dominio: el pedido cambio de estado.
 * Se publica como pedido.estado.actualizado y, si el nuevo estado es CANCELLED,
 * tambien como pedido.cancelado para que envios detenga el despacho.
 */
public record OrderStatusChangedEvent(
        UUID eventId,
        UUID orderId,
        UUID customerId,
        OrderStatus previousStatus,
        OrderStatus newStatus,
        String source,
        OffsetDateTime occurredAt) {

    public static OrderStatusChangedEvent of(
            UUID orderId,
            UUID customerId,
            OrderStatus previousStatus,
            OrderStatus newStatus,
            String source) {
        return new OrderStatusChangedEvent(
                UUID.randomUUID(), orderId, customerId, previousStatus, newStatus, source, OffsetDateTime.now());
    }

    public boolean isCancellation() {
        return newStatus == OrderStatus.CANCELLED;
    }
}
