package com.api.envios.messaging.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * Contrato del mensaje pedido.cancelado publicado por pedidos.
 */
public record OrderCancelledMessage(
        UUID eventId,
        UUID orderId,
        String previousStatus,
        String newStatus,
        OffsetDateTime occurredAt) {
}
