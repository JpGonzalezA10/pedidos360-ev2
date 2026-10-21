package com.api.inventario.messaging.event;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/*
 * Contrato del mensaje pedido.confirmado publicado por pedidos.
 * Inventario solo necesita los productos involucrados.
 */
public record OrderConfirmedMessage(
        UUID eventId,
        UUID orderId,
        List<Item> items,
        OffsetDateTime occurredAt) {

    public record Item(UUID productId, String sku, int quantity) {
    }
}
