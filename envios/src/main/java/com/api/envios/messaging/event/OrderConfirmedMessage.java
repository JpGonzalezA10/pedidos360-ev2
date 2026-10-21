package com.api.envios.messaging.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * Contrato del mensaje pedido.confirmado publicado por pedidos.
 * Solo se mapean los campos que envios necesita; los demas se ignoran.
 */
public record OrderConfirmedMessage(
        UUID eventId,
        UUID orderId,
        UUID customerId,
        String shippingAddress,
        OffsetDateTime occurredAt) {
}
