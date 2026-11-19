package com.api.pedidos.streaming.message;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * EP5 - DTO consumido desde el topico pedidos360.inventario.stock (contrato local de pedidos).
 * Es la ultima foto del stock de un SKU; el topico es compactado por key=sku.
 * Las anotaciones JSR-380 se validan en el @KafkaListener (@Valid): un payload invalido va a la DLT.
 */
public record StockSnapshotMessage(
        @NotNull UUID eventId,
        @NotNull UUID productId,
        @NotBlank String sku,
        String productName,
        @Min(0) int stockAvailable,
        @Min(0) int stockReserved,
        int stockFree,
        @Min(0) int reorderPoint,
        @NotBlank String reason,
        @NotNull OffsetDateTime occurredAt) {
}
