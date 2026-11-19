package com.api.pedidos.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * EP5 - Modelo de dominio (no JPA) de la proyeccion local de stock que pedidos mantiene
 * a partir del stream de inventario. Permite consultar disponibilidad aproximada sin llamar
 * por HTTP a inventario. La reserva real sigue siendo sincrona (InventoryClient + Circuit Breaker).
 */
public record StockSnapshot(
        UUID productId,
        String sku,
        String productName,
        int stockAvailable,
        int stockReserved,
        int reorderPoint,
        String lastReason,
        OffsetDateTime occurredAt) {

    public int stockFree() {
        return stockAvailable - stockReserved;
    }

    public boolean lowStock() {
        return stockFree() <= reorderPoint;
    }
}
