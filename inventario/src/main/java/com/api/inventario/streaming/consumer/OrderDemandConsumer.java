package com.api.inventario.streaming.consumer;

import com.api.inventario.exception.ResourceNotFoundException;
import com.api.inventario.service.DemandAnalyticsService;
import com.api.inventario.streaming.message.OrderEventMessage;
import com.api.inventario.streaming.support.NonRetryableEventException;
import com.api.inventario.streaming.support.ProcessedEventRegistry;
import jakarta.validation.Valid;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/*
 * EP5 - Consumidor Kafka de pedidos360.pedidos.eventos en inventario.
 *
 * Consumer group: inventario-demanda. Es un grupo DISTINTO al de envios (envios-seguimiento):
 * ambos leen TODOS los eventos del mismo topico de forma independiente (publish/subscribe),
 * y dentro de cada grupo las 3 particiones se reparten entre 3 consumidores (balanceo de carga).
 *
 * Solo traduce el mensaje y delega en DemandAnalyticsService (logica de dominio).
 */
@Component
public class OrderDemandConsumer {

    private static final Logger log = LoggerFactory.getLogger(OrderDemandConsumer.class);

    private final DemandAnalyticsService demandAnalyticsService;
    private final ProcessedEventRegistry processedEvents;

    public OrderDemandConsumer(DemandAnalyticsService demandAnalyticsService, ProcessedEventRegistry processedEvents) {
        this.demandAnalyticsService = demandAnalyticsService;
        this.processedEvents = processedEvents;
    }

    @KafkaListener(
            id = "inventario-demand-listener",
            topics = "${streaming.topics.pedidos-eventos.name}",
            groupId = "${streaming.consumer-groups.demanda}",
            concurrency = "${streaming.listener.concurrency:3}")
    public void onOrderEvent(
            @Payload @Valid OrderEventMessage message,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("[KAFKA-CONSUME] {} orderId={} partition={} offset={}",
                message.eventType(), message.orderId(), partition, offset);
        if (processedEvents.alreadyProcessed(message.eventId())) {
            log.info("[KAFKA-DUPLICATE] eventId={} ya procesado, se ignora", message.eventId());
            return;
        }
        try {
            switch (message.eventType()) {
                case ORDER_CONFIRMED -> demandAnalyticsService.registerConfirmedOrder(
                        message.orderId(), unitsBySku(message), message.occurredAt());
                case ORDER_CANCELLED -> demandAnalyticsService.registerCancelledOrder(message.orderId());
                case ORDER_STATUS_CHANGED -> log.debug("Cambio de estado sin impacto en la demanda");
            }
        } catch (ResourceNotFoundException exception) {
            // Error de datos (SKU inexistente): reintentar no lo arregla -> DLT.
            throw new NonRetryableEventException(exception.getMessage());
        }
        // DataAccessException (BD caida) no se captura: el DefaultErrorHandler la reintenta con backoff.
        processedEvents.markProcessed(message.eventId());
    }

    private Map<String, Integer> unitsBySku(OrderEventMessage message) {
        if (message.safeItems().isEmpty()) {
            throw new NonRetryableEventException("Pedido confirmado " + message.orderId() + " sin items");
        }
        Map<String, Integer> units = new LinkedHashMap<>();
        message.safeItems().forEach(item -> units.merge(item.sku(), item.quantity(), Integer::sum));
        return units;
    }
}
