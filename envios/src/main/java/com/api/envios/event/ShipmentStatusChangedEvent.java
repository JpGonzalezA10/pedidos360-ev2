package com.api.envios.event;

import java.time.OffsetDateTime;
import java.util.UUID;

/*
 * Evento de dominio: un envio fue creado (previousStatus null) o cambio de estado.
 * Se publica en RabbitMQ como envio.creado o envio.estado.actualizado.
 */
public record ShipmentStatusChangedEvent(
        UUID eventId,
        UUID shipmentId,
        UUID orderId,
        String previousStatus,
        String newStatus,
        OffsetDateTime occurredAt) {

    public static ShipmentStatusChangedEvent of(UUID shipmentId, UUID orderId, String previousStatus, String newStatus) {
        return new ShipmentStatusChangedEvent(
                UUID.randomUUID(), shipmentId, orderId, previousStatus, newStatus, OffsetDateTime.now());
    }

    public boolean isCreation() {
        return previousStatus == null;
    }
}
