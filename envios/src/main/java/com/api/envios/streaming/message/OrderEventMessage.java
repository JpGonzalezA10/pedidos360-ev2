package com.api.envios.streaming.message;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/*
 * EP5 - DTO consumido desde pedidos360.pedidos.eventos (contrato local de inventario).
 * Las anotaciones JSR-380 se validan en el @KafkaListener: si falta un campo obligatorio
 * el mensaje va directo a la DLT sin reintentos.
 */
public record OrderEventMessage(
        int schemaVersion,
        @NotNull UUID eventId,
        @NotNull OrderEventType eventType,
        @NotNull UUID orderId,
        UUID customerId,
        String previousStatus,
        @NotBlank String newStatus,
        BigDecimal totalAmount,
        List<@Valid Item> items,
        String source,
        @NotNull OffsetDateTime occurredAt) {

    public enum OrderEventType {
        ORDER_CONFIRMED,
        ORDER_STATUS_CHANGED,
        ORDER_CANCELLED
    }

    public record Item(
            UUID productId,
            @NotBlank String sku,
            @Min(1) int quantity) {
    }

    public List<Item> safeItems() {
        return items == null ? List.of() : items;
    }
}
