package com.api.envios.streaming.consumer;

import com.api.envios.exception.BusinessRuleException;
import com.api.envios.service.OrderTrackingService;
import com.api.envios.streaming.message.OrderEventMessage;
import com.api.envios.streaming.support.NonRetryableEventException;
import com.api.envios.streaming.support.ProcessedEventRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/*
 * EP5 - Consumidor Kafka de pedidos360.pedidos.eventos en envios.
 *
 * Consumer group: envios-seguimiento (independiente de inventario-demanda: ambos reciben todos los eventos).
 * Con key=orderId, todos los eventos de un pedido llegan en orden al mismo consumidor del grupo.
 */
@Component
public class OrderTrackingConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderTrackingConsumer.class);

    private final OrderTrackingService orderTrackingService;
    private final ProcessedEventRegistry processedEvents;

    public OrderTrackingConsumer(OrderTrackingService orderTrackingService, ProcessedEventRegistry processedEvents) {
        this.orderTrackingService = orderTrackingService;
        this.processedEvents = processedEvents;
    }

    @KafkaListener(
            id = "envios-tracking-listener",
            topics = "${streaming.topics.pedidos-eventos.name}",
            groupId = "${streaming.consumer-groups.seguimiento}",
            concurrency = "${streaming.listener.concurrency:3}")
    public void onOrderEvent(
            @Payload @Valid OrderEventMessage message,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("[KAFKA-CONSUME] {} orderId={} estado={} partition={} offset={}",
                message.eventType(), message.orderId(), message.newStatus(), partition, offset);
        if (processedEvents.alreadyProcessed(message.eventId())) {
            log.info("[KAFKA-DUPLICATE] eventId={} ya procesado, se ignora", message.eventId());
            return;
        }
        try {
            orderTrackingService.registerStatus(
                    message.orderId(), message.newStatus(), message.source(), message.occurredAt());
        } catch (BusinessRuleException exception) {
            throw new NonRetryableEventException(exception.getMessage());
        }
        processedEvents.markProcessed(message.eventId());
    }
}
