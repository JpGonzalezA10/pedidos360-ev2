package com.api.pedidos.messaging.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * Contrato del mensaje envio.estado.actualizado publicado por el microservicio envios.
 * Se define localmente (sin compartir clases entre microservicios) con solo los campos que pedidos usa.
 */
public record ShipmentStatusChangedMessage(
        UUID eventId,
        UUID shipmentId,
        UUID orderId,
        String previousStatus,
        String newStatus,
        OffsetDateTime occurredAt) {
}
