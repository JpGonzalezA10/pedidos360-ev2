package com.api.envios.messaging.producer;

import com.api.envios.event.ShipmentStatusChangedEvent;
import com.api.envios.messaging.config.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * Productor RabbitMQ del dominio envios.
 * Publica en envios.events los eventos que ShipmentService emite, solo despues del commit.
 */
@Component
public class ShipmentEventsPublisher {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventsPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public ShipmentEventsPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onShipmentStatusChanged(ShipmentStatusChangedEvent event) {
        String routingKey = event.isCreation()
                ? properties.routingKeys().envioCreado()
                : properties.routingKeys().envioEstadoActualizado();
        publish(routingKey, event, event.eventId().toString());
    }

    private void publish(String routingKey, Object payload, String correlationId) {
        String exchange = properties.exchanges().envios();
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
