package com.api.pedidos.messaging.consumer.notificaciones;

import com.api.pedidos.event.OrderStatusChangedEvent;
import com.api.pedidos.messaging.config.MessagingProperties;
import com.api.pedidos.messaging.support.MessageAcknowledger;
import com.api.pedidos.messaging.support.NonRecoverableMessageException;
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
 * Consumidor del dominio NOTIFICACIONES.
 * Cola: pedidos.notificaciones-cliente.queue <- pedidos.events / pedido.estado.actualizado
 * Genera la notificacion al cliente por cada cambio de estado de su pedido (en esta etapa: log
 * estructurado; punto de extension para correo o push). Tarea asincrona: no retrasa la API de pedidos.
 */
@Component
public class CustomerNotificationConsumer {

    private static final Logger log = LoggerFactory.getLogger(CustomerNotificationConsumer.class);

    private final MessageAcknowledger acknowledger;
    private final SmartMessageConverter converter;
    private final MessagingProperties properties;

    public CustomerNotificationConsumer(
            MessageAcknowledger acknowledger,
            SmartMessageConverter converter,
            MessagingProperties properties) {
        this.acknowledger = acknowledger;
        this.converter = converter;
        this.properties = properties;
    }

    @RabbitListener(id = "pedidos-notificaciones-cliente", queues = "${messaging.queues.notificaciones-cliente}",
            ackMode = "MANUAL")
    public void onOrderStatusChanged(Message message, Channel channel) throws IOException {
        try {
            Object payload = converter.fromMessage(message,
                    ParameterizedTypeReference.forType(OrderStatusChangedEvent.class));
            if (!(payload instanceof OrderStatusChangedEvent event)
                    || event.orderId() == null || event.customerId() == null || event.newStatus() == null) {
                throw new NonRecoverableMessageException("pedido.estado.actualizado sin orderId, customerId o estado");
            }
            log.info("[NOTIFICACION CLIENTE] cliente={} : su pedido {} cambio de {} a {} (origen {})",
                    event.customerId(), event.orderId(), event.previousStatus(), event.newStatus(), event.source());
            acknowledger.ack(channel, message);
        } catch (Exception error) {
            acknowledger.handleFailure(channel, message, error, properties.queues().notificacionesClienteRetry());
        }
    }
}
