package com.api.envios.messaging.consumer.pedidos;

import com.api.envios.messaging.config.MessagingProperties;
import com.api.envios.messaging.event.OrderCancelledMessage;
import com.api.envios.messaging.event.OrderConfirmedMessage;
import com.api.envios.messaging.support.MessageAcknowledger;
import com.api.envios.messaging.support.NonRecoverableMessageException;
import com.api.envios.service.ShipmentService;
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
 * Consumidores del dominio PEDIDOS dentro de envios.
 *  - envios.pedido-confirmado.queue <- pedido.confirmado : crea el despacho.
 *  - envios.pedido-cancelado.queue  <- pedido.cancelado  : cancela el despacho pendiente.
 *
 * ACK manual: exito -> basicAck; error permanente -> nack sin requeue (DLQ);
 * error transitorio -> cola retry con TTL hasta agotar intentos -> DLQ.
 */
@Component
public class OrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConsumer.class);

    private final ShipmentService shipmentService;
    private final MessageAcknowledger acknowledger;
    private final SmartMessageConverter converter;
    private final MessagingProperties properties;

    public OrderEventsConsumer(
            ShipmentService shipmentService,
            MessageAcknowledger acknowledger,
            SmartMessageConverter converter,
            MessagingProperties properties) {
        this.shipmentService = shipmentService;
        this.acknowledger = acknowledger;
        this.converter = converter;
        this.properties = properties;
    }

    @RabbitListener(id = "envios-pedido-confirmado", queues = "${messaging.queues.pedido-confirmado}", ackMode = "MANUAL")
    public void onOrderConfirmed(Message message, Channel channel) throws IOException {
        try {
            OrderConfirmedMessage event = read(message, OrderConfirmedMessage.class);
            if (event.orderId() == null) {
                throw new NonRecoverableMessageException("pedido.confirmado sin orderId");
            }
            log.info("[CONSUMER pedidos->envios] pedido.confirmado pedido={}", event.orderId());
            shipmentService.createFromConfirmedOrder(event.orderId(), event.shippingAddress())
                    .ifPresent(shipment -> log.info("Envio {} creado para pedido {}", shipment.id(), event.orderId()));
            acknowledger.ack(channel, message);
        } catch (Exception error) {
            acknowledger.handleFailure(channel, message, error, properties.queues().pedidoConfirmadoRetry());
        }
    }

    @RabbitListener(id = "envios-pedido-cancelado", queues = "${messaging.queues.pedido-cancelado}", ackMode = "MANUAL")
    public void onOrderCancelled(Message message, Channel channel) throws IOException {
        try {
            OrderCancelledMessage event = read(message, OrderCancelledMessage.class);
            if (event.orderId() == null) {
                throw new NonRecoverableMessageException("pedido.cancelado sin orderId");
            }
            log.info("[CONSUMER pedidos->envios] pedido.cancelado pedido={}", event.orderId());
            shipmentService.cancelForCancelledOrder(event.orderId());
            acknowledger.ack(channel, message);
        } catch (Exception error) {
            acknowledger.handleFailure(channel, message, error, properties.queues().pedidoCanceladoRetry());
        }
    }

    /** Convierte el JSON al contrato esperado; un payload de otro tipo es un error permanente. */
    private <T> T read(Message message, Class<T> type) {
        Object payload = converter.fromMessage(message, ParameterizedTypeReference.forType(type));
        if (!type.isInstance(payload)) {
            throw new NonRecoverableMessageException("Payload no corresponde a " + type.getSimpleName());
        }
        return type.cast(payload);
    }
}
