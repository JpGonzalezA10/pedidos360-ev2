package com.api.inventario.event;

import com.api.inventario.model.Inventory;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * EP5 - Evento de dominio: el stock de un producto cambio (ajuste, reserva, liberacion o venta).
 * InventoryService lo emite con ApplicationEventPublisher; la capa streaming lo publica en Kafka
 * despues del commit. El servicio no conoce Kafka.
 */
public record StockChangedEvent(
        UUID eventId,
        UUID productId,
        String sku,
        String productName,
        int stockAvailable,
        int stockReserved,
        int reorderPoint,
        Reason reason,
        OffsetDateTime occurredAt) {

    public enum Reason {
        ADJUSTED,
        RESERVED,
        RELEASED,
        SOLD,
        SNAPSHOT
    }

    public static StockChangedEvent of(Inventory inventory, Reason reason) {
        return new StockChangedEvent(
                UUID.randomUUID(),
                inventory.getProduct().getId(),
                inventory.getProduct().getSku(),
                inventory.getProduct().getName(),
                inventory.getStockAvailable(),
                inventory.getStockReserved(),
                inventory.getReorderPoint(),
                reason,
                OffsetDateTime.now());
    }
}
