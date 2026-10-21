package com.api.inventario.messaging.producer;

import com.api.inventario.event.LowStockDetectedEvent;
import com.api.inventario.messaging.config.MessagingProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * Productor RabbitMQ del dominio inventario.
 * Publica inventario.stock.bajo en inventario.events cuando StockAlertService detecta stock bajo.
 */
@Component
public class StockEventsPublisher {

    private static final Logger log = LoggerFactory.getLogger(StockEventsPublisher.class);

    private final RabbitTemplate rabbitTemplate;
    private final MessagingProperties properties;

    public StockEventsPublisher(RabbitTemplate rabbitTemplate, MessagingProperties properties) {
        this.rabbitTemplate = rabbitTemplate;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onLowStockDetected(LowStockDetectedEvent event) {
        String exchange = properties.exchanges().inventario();
        String routingKey = properties.routingKeys().stockBajo();
        String messageId = event.eventId().toString();
        try {
            rabbitTemplate.convertAndSend(exchange, routingKey, event, message -> {
                message.getMessageProperties().setMessageId(messageId);
                return message;
            }, new CorrelationData(messageId));
            log.info("[PUBLISH] exchange={} routingKey={} sku={} stockLibre={}",
                    exchange, routingKey, event.sku(), event.stockFree());
        } catch (AmqpException exception) {
            log.error("[PUBLISH-ERROR] No se pudo publicar stock bajo sku={}: {}", event.sku(), exception.getMessage());
        }
    }
}
