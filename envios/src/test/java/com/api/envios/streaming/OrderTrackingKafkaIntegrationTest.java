package com.api.envios.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.api.envios.service.OrderTrackingService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

/*
 * EP5 - Prueba de extremo a extremo del consumidor Kafka de envios contra un broker embebido
 * (en la JVM, sin Docker): mensaje valido -> proyeccion; mensajes defectuosos -> DLT con headers de error,
 * sin detener el consumidor (el mensaje valido posterior se sigue procesando).
 */
@SpringBootTest(properties = {
        "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
        "spring.kafka.listener.auto-startup=true",
        "spring.kafka.admin.auto-create=true",
        "streaming.topics.pedidos-eventos.replicas=1",
        "streaming.dead-letter.replicas=1",
        "streaming.dead-letter.configs.[min.insync.replicas]=1",
        "streaming.retry.initial-interval-ms=100",
        "streaming.retry.max-interval-ms=200"
})
@EmbeddedKafka(count = 1, partitions = 3, topics = "pedidos360.pedidos.eventos")
class OrderTrackingKafkaIntegrationTest {

    private static final String TOPIC = "pedidos360.pedidos.eventos";
    private static final String DLT = TOPIC + ".DLT";

    @Autowired
    private OrderTrackingService orderTrackingService;

    @Value("${spring.embedded.kafka.brokers}")
    private String brokers;

    @Test
    void validEventsAreProjectedAndDefectiveOnesGoToDeadLetterTopic() {
        UUID orderId = UUID.randomUUID();
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            // 1) JSON corrupto -> ErrorHandlingDeserializer -> DLT (sin bucle infinito).
            producer.send(new ProducerRecord<>(TOPIC, "corrupto", "{esto no es json"));
            // 2) Falta orderId (@NotNull) -> @Valid falla -> DLT sin reintentos.
            producer.send(new ProducerRecord<>(TOPIC, "invalido", event(null, "CONFIRMED")));
            // 3) Estado desconocido -> regla de negocio -> NonRetryableEventException -> DLT.
            producer.send(new ProducerRecord<>(TOPIC, orderId.toString(), event(orderId, "TELETRANSPORTADO")));
            // 4) Evento valido -> proyeccion de seguimiento.
            producer.send(new ProducerRecord<>(TOPIC, orderId.toString(), event(orderId, "CONFIRMED")));
            producer.flush();
        }

        await().atMost(Duration.ofSeconds(30)).ignoreExceptions().untilAsserted(() ->
                assertThat(orderTrackingService.findTimeline(orderId).currentStatus()).isEqualTo("CONFIRMED"));
        assertThat(orderTrackingService.pendingDispatch()).contains(orderId);

        List<ConsumerRecord<String, String>> deadLetters = readDeadLetters(3);
        assertThat(deadLetters).extracting(ConsumerRecord::key)
                .containsExactlyInAnyOrder("corrupto", "invalido", orderId.toString());
        assertThat(deadLetters).allSatisfy(record -> {
            assertThat(header(record, "kafka_dlt-original-topic")).isEqualTo(TOPIC);
            assertThat(header(record, "kafka_dlt-exception-message")).isNotBlank();
        });
        // El JSON corrupto se conserva byte a byte en la DLT para poder corregirlo y reprocesarlo.
        assertThat(deadLetters).filteredOn(record -> "corrupto".equals(record.key()))
                .singleElement()
                .satisfies(record -> assertThat(record.value()).isEqualTo("{esto no es json"));
    }

    private List<ConsumerRecord<String, String>> readDeadLetters(int expected) {
        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-dlt-reader",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of(DLT));
            long deadline = System.currentTimeMillis() + 30_000;
            while (records.size() < expected && System.currentTimeMillis() < deadline) {
                consumer.poll(Duration.ofMillis(500)).forEach(records::add);
            }
        }
        return records;
    }

    private static String event(UUID orderId, String status) {
        return """
                {"schemaVersion":1,"eventId":"%s","eventType":"ORDER_CONFIRMED",%s"customerId":"%s",
                 "newStatus":"%s","items":[{"sku":"SLX-ELE-002","quantity":2}],"source":"API",
                 "occurredAt":"2026-11-18T10:00:00Z"}
                """.formatted(UUID.randomUUID(), orderId == null ? "" : "\"orderId\":\"" + orderId + "\",",
                UUID.randomUUID(), status);
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }
}
