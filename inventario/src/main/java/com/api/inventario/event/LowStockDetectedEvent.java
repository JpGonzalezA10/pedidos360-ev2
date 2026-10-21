package com.api.inventario.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * Evento de dominio: el stock libre de un producto quedo en o bajo su punto de reposicion.
 * Se publica en RabbitMQ como inventario.stock.bajo para notificar al operador de inventario.
 */
public record LowStockDetectedEvent(
        UUID eventId,
        UUID productId,
        String sku,
        String productName,
        int stockAvailable,
        int stockReserved,
        int stockFree,
        int reorderPoint,
        UUID triggeredByOrderId,
        OffsetDateTime occurredAt) {
}
