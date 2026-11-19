package com.api.inventario.streaming.message;

import com.api.inventario.event.StockChangedEvent;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * EP5 - DTO publicado en pedidos360.inventario.stock (key=sku, topico compactado).
 * Foto completa del stock del SKU: el consumidor no necesita estado previo para interpretarla.
 */
public record StockSnapshotMessage(
        UUID eventId,
        UUID productId,
        String sku,
        String productName,
        int stockAvailable,
        int stockReserved,
        int stockFree,
        int reorderPoint,
        String reason,
        OffsetDateTime occurredAt) {

    public static StockSnapshotMessage from(StockChangedEvent event) {
        return new StockSnapshotMessage(
                event.eventId(),
                event.productId(),
                event.sku(),
                event.productName(),
                event.stockAvailable(),
                event.stockReserved(),
                event.stockAvailable() - event.stockReserved(),
                event.reorderPoint(),
                event.reason().name(),
                event.occurredAt());
    }
}
