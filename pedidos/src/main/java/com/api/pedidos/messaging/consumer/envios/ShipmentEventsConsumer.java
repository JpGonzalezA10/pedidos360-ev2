package com.api.pedidos.messaging.consumer.envios;

import com.api.pedidos.messaging.config.MessagingProperties;
import com.api.pedidos.messaging.event.ShipmentStatusChangedMessage;
import com.api.pedidos.messaging.support.MessageAcknowledger;
import com.api.pedidos.messaging.support.NonRecoverableMessageException;
import com.api.pedidos.service.OrderService;
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
 * Consumidor del dominio ENVIOS dentro de pedidos.
 * Cola: pedidos.envio-estado.queue  <-  envios.events / envio.estado.actualizado
 *
 * ACK manual:
 *  - exito                         -> basicAck
 *  - payload invalido / no existe  -> basicNack(requeue=false) -> DLQ
 *  - error transitorio             -> cola retry (TTL) hasta agotar intentos -> DLQ
 */
@Component
public class ShipmentEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(ShipmentEventsConsumer.class);

    private final OrderService orderService;
    private final MessageAcknowledger acknowledger;
    private final SmartMessageConverter converter;
    private final MessagingProperties properties;

    public ShipmentEventsConsumer(
            OrderService orderService,
            MessageAcknowledger acknowledger,
            SmartMessageConverter converter,
            MessagingProperties properties) {
        this.orderService = orderService;
        this.acknowledger = acknowledger;
        this.converter = converter;
        this.properties = properties;
    }

    @RabbitListener(id = "pedidos-envio-estado", queues = "${messaging.queues.envio-estado}", ackMode = "MANUAL")
    public void onShipmentStatusChanged(Message message, Channel channel) throws IOException {
        try {
            ShipmentStatusChangedMessage event = read(message);
            log.info("[CONSUMER envios->pedidos] pedido={} envio={} estado {} -> {}",
                    event.orderId(), event.shipmentId(), event.previousStatus(), event.newStatus());
            orderService.applyShipmentStatus(event.orderId(), event.newStatus());
            acknowledger.ack(channel, message);
        } catch (Exception error) {
            acknowledger.handleFailure(channel, message, error, properties.queues().envioEstadoRetry());
        }
    }

    /** Convierte y valida el payload; si falta informacion obligatoria el error es permanente. */
    private ShipmentStatusChangedMessage read(Message message) {
        Object payload = converter.fromMessage(message,
                ParameterizedTypeReference.forType(ShipmentStatusChangedMessage.class));
        if (!(payload instanceof ShipmentStatusChangedMessage event)) {
            throw new NonRecoverableMessageException("Payload no corresponde a envio.estado.actualizado");
        }
        if (event.orderId() == null || event.newStatus() == null || event.newStatus().isBlank()) {
            throw new NonRecoverableMessageException("Mensaje sin orderId o newStatus");
        }
        return event;
    }
}
