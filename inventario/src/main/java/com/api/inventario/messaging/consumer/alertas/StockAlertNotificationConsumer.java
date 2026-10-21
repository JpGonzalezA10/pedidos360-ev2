package com.api.inventario.messaging.consumer.alertas;

import com.api.inventario.event.LowStockDetectedEvent;
import com.api.inventario.messaging.config.MessagingProperties;
import com.api.inventario.messaging.support.MessageAcknowledger;
import com.api.inventario.messaging.support.NonRecoverableMessageException;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.SmartMessageConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/*
 * Consumidor del dominio ALERTAS / NOTIFICACIONES.
 * Cola: inventario.alertas-stock.queue <- inventario.events / inventario.stock.bajo
 * Genera la notificacion de reposicion para el OPERADOR_INVENTARIO (en esta etapa: log estructurado,
 * punto de extension para correo, Slack o ticket).
 */
@Component
public class StockAlertNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(StockAlertNotificationConsumer.class);

    private final MessageAcknowledger acknowledger;
    private final SmartMessageConverter converter;
    private final MessagingProperties properties;

    public StockAlertNotificationConsumer(
            MessageAcknowledger acknowledger,
            SmartMessageConverter converter,
            MessagingProperties properties) {
        this.acknowledger = acknowledger;
        this.converter = converter;
        this.properties = properties;
    }

    @RabbitListener(id = "inventario-alertas-stock", queues = "${messaging.queues.alertas-stock}", ackMode = "MANUAL")
    public void onLowStock(Message message, Channel channel) throws IOException {
        try {
            Object payload = converter.fromMessage(message,
                    ParameterizedTypeReference.forType(LowStockDetectedEvent.class));
            if (!(payload instanceof LowStockDetectedEvent alert) || alert.sku() == null) {
                throw new NonRecoverableMessageException("Payload no corresponde a inventario.stock.bajo");
            }
            log.warn("[NOTIFICACION OPERADOR_INVENTARIO] Reponer SKU {} ({}): stock libre {} <= punto de reposicion {}"
                            + " (disponible={}, reservado={}, pedido={})",
                    alert.sku(), alert.productName(), alert.stockFree(), alert.reorderPoint(),
                    alert.stockAvailable(), alert.stockReserved(), alert.triggeredByOrderId());
            acknowledger.ack(channel, message);
        } catch (Exception error) {
            acknowledger.handleFailure(channel, message, error, properties.queues().alertasStockRetry());
        }
    }
}
