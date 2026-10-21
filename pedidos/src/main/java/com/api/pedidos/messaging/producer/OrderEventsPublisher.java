package com.api.pedidos.messaging.producer;

import com.api.pedidos.event.OrderConfirmedEvent;
import com.api.pedidos.event.OrderStatusChangedEvent;
import com.api.pedidos.messaging.config.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * Productor RabbitMQ del dominio pedidos.
 * Escucha los eventos de dominio que publica OrderService y los envia a pedidos.events
 * SOLO despues del commit: si la transaccion falla, no se anuncia un pedido inexistente.
 * Un fallo de RabbitMQ no revierte el pedido (ya confirmado); queda registrado en logs.
 */
@Component
public class OrderEventsPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public OrderEventsPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        publish(properties.routingKeys().pedidoConfirmado(), event, event.eventId().toString());
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        publish(properties.routingKeys().pedidoEstadoActualizado(), event, event.eventId().toString());
        if (event.isCancellation()) {
            publish(properties.routingKeys().pedidoCancelado(), event, event.eventId() + "-cancel");
        }
    }

    private void publish(String routingKey, Object payload, String correlationId) {
        String exchange = properties.exchanges().pedidos();
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, payload, message -> {
                message.getMessageProperties().setMessageId(correlationId);
                return message;
            }, new CorrelationData(correlationId));
            log.info("[PUBLISH] exchange={} routingKey={} messageId={}", exchange, routingKey, correlationId);
        } catch (AmqpException exception) {
            log.error("[PUBLISH-ERROR] No se pudo publicar exchange={} routingKey={} messageId={}: {}",
                    exchange, routingKey, correlationId, exception.getMessage());
        }
    }
}
