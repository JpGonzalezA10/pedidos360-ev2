package com.api.inventario.messaging.consumer.pedidos;

import com.api.inventario.messaging.config.MessagingProperties;
import com.api.inventario.messaging.event.OrderConfirmedMessage;
import com.api.inventario.messaging.support.MessageAcknowledger;
import com.api.inventario.messaging.support.NonRecoverableMessageException;
import com.api.inventario.service.StockAlertService;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.support.converter.SmartMessageConverter;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;

/*
 * Consumidor del dominio PEDIDOS dentro de inventario.
 * Cola: inventario.pedido-confirmado.queue <- pedidos.events / pedido.confirmado
 * Evalua si los productos del pedido quedaron con stock bajo.
 *
 * ACK manual: exito -> basicAck; payload invalido -> nack sin requeue (DLQ);
 * error transitorio (BD) -> cola retry con TTL hasta agotar intentos -> DLQ.
 */
@Component
public class OrderEventsConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderEventsConsumer.class);

    private final StockAlertService stockAlertService;
    private final MessageAcknowledger acknowledger;
    private final SmartMessageConverter converter;
    private final MessagingProperties properties;

    public OrderEventsConsumer(
            StockAlertService stockAlertService,
            MessageAcknowledger acknowledger,
            SmartMessageConverter converter,
            MessagingProperties properties) {
        this.stockAlertService = stockAlertService;
        this.acknowledger = acknowledger;
        this.converter = converter;
        this.properties = properties;
    }

    @RabbitListener(id = "inventario-pedido-confirmado", queues = "${messaging.queues.pedido-confirmado}", ackMode = "MANUAL")
    public void onOrderConfirmed(Message message, Channel channel) throws IOException {
        try {
            OrderConfirmedMessage event = read(message);
            List<UUID> productIds = event.items().stream()
                    .map(OrderConfirmedMessage.Item::productId)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
            int alerts = stockAlertService.evaluateProducts(productIds, event.orderId()).size();
            log.info("[CONSUMER pedidos->inventario] pedido={} productos={} alertasStockBajo={}",
                    event.orderId(), productIds.size(), alerts);
            acknowledger.ack(channel, message);
        } catch (Exception error) {
            acknowledger.handleFailure(channel, message, error, properties.queues().pedidoConfirmadoRetry());
        }
    }

    private OrderConfirmedMessage read(Message message) {
        Object payload = converter.fromMessage(message,
                ParameterizedTypeReference.forType(OrderConfirmedMessage.class));
        if (!(payload instanceof OrderConfirmedMessage event)) {
            throw new NonRecoverableMessageException("Payload no corresponde a pedido.confirmado");
        }
        if (event.orderId() == null || event.items() == null || event.items().isEmpty()) {
            throw new NonRecoverableMessageException("pedido.confirmado sin orderId o sin items");
        }
        return event;
    }
}
