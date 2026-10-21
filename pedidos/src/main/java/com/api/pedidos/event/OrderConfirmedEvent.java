package com.api.pedidos.event;

import com.api.pedidos.model.Order;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/*
 * Evento de dominio: un pedido fue creado y su stock quedo reservado (estado CONFIRMED).
 * Se publica en RabbitMQ como pedido.confirmado. Contiene lo necesario para que envios
 * cree el despacho sin volver a consultar a pedidos.
 */
public record OrderConfirmedEvent(
        UUID eventId,
        UUID orderId,
        UUID customerId,
        String shippingAddress,
        BigDecimal totalAmount,
        List<Item> items,
        OffsetDateTime occurredAt) {

    public record Item(UUID productId, String sku, int quantity) {
    }

    public static OrderConfirmedEvent from(Order order) {
        List<Item> items = order.getItems().stream()
                .map(item -> new Item(item.getProductId(), item.getSku(), item.getQuantity()))
                .toList();
        return new OrderConfirmedEvent(
                UUID.randomUUID(),
                order.getId(),
                order.getCustomerId(),
                order.getShippingAddress(),
                order.getTotalAmount(),
                items,
                OffsetDateTime.now());
    }
}
