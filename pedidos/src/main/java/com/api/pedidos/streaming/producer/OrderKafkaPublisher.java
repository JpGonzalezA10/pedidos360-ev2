package com.api.pedidos.streaming.producer;

import com.api.pedidos.event.OrderConfirmedEvent;
import com.api.pedidos.event.OrderStatusChangedEvent;
import com.api.pedidos.streaming.config.StreamingProperties;
import com.api.pedidos.streaming.message.OrderEventMessage;
import com.api.pedidos.streaming.support.KafkaHeadersNames;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/*
 * EP5 - Productor Kafka del dominio pedidos -> topico pedidos360.pedidos.eventos.
 *
 * Escucha los MISMOS eventos de dominio que ya publica OrderService para RabbitMQ (EP3); por eso
 * agregar Kafka no modifica la logica de negocio ni la integracion RabbitMQ existente:
 *  - RabbitMQ sigue orquestando acciones (crear despacho, cancelar envio).
 *  - Kafka guarda el historial de eventos para analitica y para multiples consumidores.
 *
 * Solo publica despues del commit. key=orderId garantiza orden por pedido. El envio es asincrono:
 * el callback registra particion/offset o el error, sin bloquear el hilo HTTP.
 */
@Component
public class OrderKafkaPublisher {

    private static final Logger log = LoggerFactory.getLogger(OrderKafkaPublisher.class);
    private static final String SOURCE_SERVICE = "pedidos";

    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final StreamingProperties properties;

    public OrderKafkaPublisher(KafkaTemplate<String, Object> kafkaTemplate, StreamingProperties properties) {
        this.kafkaTemplate = kafkaTemplate;
        this.properties = properties;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderConfirmed(OrderConfirmedEvent event) {
        publish(OrderEventMessage.confirmed(event));
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderStatusChanged(OrderStatusChangedEvent event) {
        publish(OrderEventMessage.statusChanged(event));
    }

    void publish(OrderEventMessage message) {
        String topic = properties.topics().pedidosEventos().name();
        String key = message.orderId().toString();
        ProducerRecord<String, Object> record = new ProducerRecord<>(topic, key, message);
        record.headers()
                .add(KafkaHeadersNames.CORRELATION_ID, bytes(message.eventId().toString()))
                .add(KafkaHeadersNames.EVENT_TYPE, bytes(message.eventType().name()))
                .add(KafkaHeadersNames.SOURCE_SERVICE, bytes(SOURCE_SERVICE));
        try {
            kafkaTemplate.send(record).whenComplete((result, exception) -> {
                if (exception != null) {
                    log.error("[KAFKA-PUBLISH-ERROR] topic={} key={} eventType={} eventId={}: {}",
                            topic, key, message.eventType(), message.eventId(), exception.getMessage());
                    return;
                }
                log.info("[KAFKA-PUBLISH] topic={} partition={} offset={} key={} eventType={} eventId={}",
                        topic, result.getRecordMetadata().partition(), result.getRecordMetadata().offset(),
                        key, message.eventType(), message.eventId());
            });
        } catch (RuntimeException exception) {
            // Kafka no disponible al encolar: el pedido ya esta confirmado, solo se registra el fallo.
            log.error("[KAFKA-PUBLISH-ERROR] topic={} key={} eventId={}: {}",
                    topic, key, message.eventId(), exception.getMessage());
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
