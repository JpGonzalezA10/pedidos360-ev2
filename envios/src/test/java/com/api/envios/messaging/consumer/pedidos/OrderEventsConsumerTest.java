package com.api.envios.messaging.consumer.pedidos;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.api.envios.messaging.config.MessagingProperties;
import com.api.envios.messaging.support.MessageAcknowledger;
import com.api.envios.service.ShipmentService;
import com.rabbitmq.client.Channel;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;

/*
 * Prueba del consumidor con el mensaje tal como lo publica pedidos:
 * JSON + header __TypeId__ con una clase que NO existe en envios.
 * El consumidor debe convertir al contrato local y confirmar (ACK) el mensaje.
 */
class OrderEventsConsumerTest {

    private static final long TAG = 3L;

    private ShipmentService shipmentService;
    private Channel channel;
    private OrderEventsConsumer consumer;

    @BeforeEach
    void setUp() {
        shipmentService = mock(ShipmentService.class);
        channel = mock(Channel.class);
        MessagingProperties properties = new MessagingProperties(
                new MessagingProperties.Exchanges("envios.events", "pedidos.events", "envios.dlx"),
                new MessagingProperties.RoutingKeys("envio.creado", "envio.estado.actualizado",
                        "pedido.confirmado", "pedido.cancelado"),
                new MessagingProperties.Queues("q", "q.retry", "q.dlq", "q2", "q2.retry", "q2.dlq"),
                new MessagingProperties.Retry(3, 5000),
                new MessagingProperties.Dlq(60000, 100));
        consumer = new OrderEventsConsumer(
                shipmentService,
                new MessageAcknowledger(mock(RabbitTemplate.class), properties),
                new JacksonJsonMessageConverter(),
                properties);
    }

    @Test
    void confirmedOrderFromPedidosCreatesShipmentAndAcks() throws Exception {
        UUID orderId = UUID.randomUUID();
        when(shipmentService.createFromConfirmedOrder(eq(orderId), any())).thenReturn(Optional.empty());

        consumer.onOrderConfirmed(pedidosMessage("""
                {"eventId":"%s","orderId":"%s","customerId":"%s","shippingAddress":"Av. Siempre Viva 742",
                 "totalAmount":19990.00,"items":[{"productId":"%s","sku":"SKU-1","quantity":2}],
                 "occurredAt":"2026-10-20T23:44:09.950Z"}
                """.formatted(UUID.randomUUID(), orderId, UUID.randomUUID(), UUID.randomUUID())), channel);

        verify(shipmentService).createFromConfirmedOrder(orderId, "Av. Siempre Viva 742");
        verify(channel).basicAck(TAG, false);
    }

    @Test
    void messageWithoutOrderIdIsSentToDeadLetter() throws Exception {
        consumer.onOrderConfirmed(pedidosMessage("{\"shippingAddress\":\"sin pedido\"}"), channel);

        verify(shipmentService, never()).createFromConfirmedOrder(any(), any());
        verify(channel).basicNack(TAG, false, false);
    }

    private Message pedidosMessage(String json) {
        MessageProperties props = new MessageProperties();
        props.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        props.setContentEncoding("UTF-8");
        // Header que agrega el JacksonJsonMessageConverter de pedidos al publicar.
        props.setHeader("__TypeId__", "com.api.pedidos.event.OrderConfirmedEvent");
        props.setDeliveryTag(TAG);
        return new Message(json.getBytes(StandardCharsets.UTF_8), props);
    }
}
