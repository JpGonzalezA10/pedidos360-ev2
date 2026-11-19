package com.api.pedidos.streaming.consumer;

import com.api.pedidos.exception.BusinessRuleException;
import com.api.pedidos.model.StockSnapshot;
import com.api.pedidos.service.StockProjectionService;
import com.api.pedidos.streaming.message.StockSnapshotMessage;
import com.api.pedidos.streaming.support.NonRetryableEventException;
import com.api.pedidos.streaming.support.ProcessedEventRegistry;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;

/*
 * EP5 - Consumidor Kafka del topico pedidos360.inventario.stock.
 *
 * Consumer group: pedidos-stock-cache (concurrency=3 -> 3 consumidores, uno por particion).
 * Capa de infraestructura: valida (@Valid), descarta duplicados, traduce el DTO al modelo de dominio
 * y delega en StockProjectionService. Los errores de negocio se convierten en NonRetryableEventException
 * para que el DefaultErrorHandler los envie a la DLT sin reintentar.
 */
@Component
public class StockSnapshotConsumer {

    private static final Logger log = LoggerFactory.getLogger(StockSnapshotConsumer.class);

    private final StockProjectionService stockProjectionService;
    private final ProcessedEventRegistry processedEvents;

    public StockSnapshotConsumer(StockProjectionService stockProjectionService, ProcessedEventRegistry processedEvents) {
        this.stockProjectionService = stockProjectionService;
        this.processedEvents = processedEvents;
    }

    @KafkaListener(
            id = "pedidos-stock-snapshot-listener",
            topics = "${streaming.topics.inventario-stock.name}",
            groupId = "${streaming.consumer-groups.stock-cache}",
            concurrency = "${streaming.listener.concurrency:3}")
    public void onStockSnapshot(
            @Payload @Valid StockSnapshotMessage message,
            @Header(KafkaHeaders.RECEIVED_PARTITION) int partition,
            @Header(KafkaHeaders.OFFSET) long offset) {
        log.info("[KAFKA-CONSUME] stock sku={} partition={} offset={} eventId={}",
                message.sku(), partition, offset, message.eventId());
        if (processedEvents.alreadyProcessed(message.eventId())) {
            log.info("[KAFKA-DUPLICATE] eventId={} ya procesado, se ignora", message.eventId());
            return;
        }
        try {
            stockProjectionService.apply(toDomain(message));
            processedEvents.markProcessed(message.eventId());
        } catch (BusinessRuleException exception) {
            throw new NonRetryableEventException(exception.getMessage());
        }
    }

    private StockSnapshot toDomain(StockSnapshotMessage message) {
        return new StockSnapshot(
                message.productId(),
                message.sku(),
                message.productName(),
                message.stockAvailable(),
                message.stockReserved(),
                message.reorderPoint(),
                message.reason(),
                message.occurredAt());
    }
}
