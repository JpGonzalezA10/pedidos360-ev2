package com.api.inventario.streaming.producer;

import com.api.inventario.event.StockChangedEvent;
import com.api.inventario.streaming.config.StreamingProperties;
import com.api.inventario.streaming.message.StockSnapshotMessage;
import com.api.inventario.streaming.support.KafkaHeadersNames;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * EP5 - Productor Kafka de inventario -> topico pedidos360.inventario.stock.
 * Publica una foto del stock despues de cada commit que lo modifica. key=sku: Kafka conserva
 * (compactacion) el ultimo valor por SKU y todos los cambios de un SKU van a la misma particion.
 */
@Component
public class StockKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(StockKafkaPublisher.class);
    private static final String SOURCE_SERVICE = "inventario";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StreamingProperties properties;

    public StockKafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate, StreamingProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onStockChanged(StockChangedEvent event) {
        StockSnapshotMessage message = StockSnapshotMessage.from(event);
        String topic = properties.topics().inventarioStock().name();
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, message.sku(), message);
        record.headers()
                .add(KafkaHeadersNames.CORRELATION_ID, bytes(message.eventId().toString()))
                .add(KafkaHeadersNames.EVENT_TYPE, bytes("STOCK_" + message.reason()))
                .add(KafkaHeadersNames.SOURCE_SERVICE, bytes(SOURCE_SERVICE));
        try {
            kafkaTemplate.send(record).whenComplete((result, exception) -> {
                if (exception != null) {
                    log.error("[KAFKA-PUBLISH-ERROR] topic={} key={} eventId={}: {}",
                            topic, message.sku(), message.eventId(), exception.getMessage());
                    return;
                }
                log.info("[KAFKA-PUBLISH] topic={} partition={} offset={} key={} reason={} libre={}",
                        topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset(),
                        message.sku(), message.reason(), message.stockFree());
            });
        } catch (RuntimeException exception) {
            log.error("[KAFKA-PUBLISH-ERROR] topic={} key={} eventId={}: {}",
                    topic, message.sku(), message.eventId(), exception.getMessage());
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
