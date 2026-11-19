package com.api.kafkaadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.api.kafkaadmin.dto.AdminResponses.ConsumerGroupDetail;
import com.api.kafkaadmin.dto.AdminResponses.DeadLetterSummary;
import com.api.kafkaadmin.dto.AdminResponses.ReprocessResult;
import com.api.kafkaadmin.dto.AdminResponses.TopicDetail;
import com.api.kafkaadmin.dto.OffsetResetRequest;
import com.api.kafkaadmin.dto.TopicConfigUpdateRequest;
import com.api.kafkaadmin.dto.TopicCreateRequest;
import com.api.kafkaadmin.exception.InvalidConfigurationException;
import com.api.kafkaadmin.exception.ProtectedResourceException;
import com.api.kafkaadmin.exception.ResourceConflictException;
import com.api.kafkaadmin.exception.ResourceNotFoundException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.test.context.EmbeddedKafka;

/*
 * EP5 - Pruebas de integracion del administrador contra un broker Kafka embebido (corre en la JVM
 * de la prueba, sin Docker). Verifica el ciclo completo de topicos, consumer groups y DLT.
 *
 * El escenario que ELIMINA un topico se ejecuta al final: en Windows el broker embebido no puede
 * renombrar el log borrado (bloqueo de archivos del SO) y deja su directorio offline. En Linux
 * (EC2 / contenedores) no ocurre.
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(count = 1, partitions = 1)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class KafkaAdminServiceIntegrationTest {

    @Autowired
    private KafkaAdminService kafkaAdminService;

    @Autowired
    private DeadLetterService deadLetterService;

    @Value("${spring.embedded.kafka.brokers}")
    private String brokers;

    @Order(Integer.MAX_VALUE)
    @Test
    void topicLifecycleCreateDescribeAlterIncreaseAndDelete() {
        TopicDetail created = kafkaAdminService.createTopic(new TopicCreateRequest(
                "test.lifecycle", 2, (short) 1,
                Map.of("cleanup.policy", "delete", "retention.ms", "3600000")));
        assertThat(created.partitions()).hasSize(2);
        assertThat(created.configs()).containsEntry("retention.ms", "3600000");

        TopicDetail altered = kafkaAdminService.updateTopicConfigs("test.lifecycle",
                new TopicConfigUpdateRequest(Map.of("retention.ms", "7200000", "cleanup.policy", "compact"), null));
        assertThat(altered.configs())
                .containsEntry("retention.ms", "7200000")
                .containsEntry("cleanup.policy", "compact");

        TopicDetail increased = kafkaAdminService.increasePartitions("test.lifecycle", 4);
        assertThat(increased.partitions()).hasSize(4);

        assertThat(kafkaAdminService.listTopics(false))
                .anySatisfy(topic -> assertThat(topic.name()).isEqualTo("test.lifecycle"));

        kafkaAdminService.deleteTopic("test.lifecycle");
        assertThatThrownBy(() -> kafkaAdminService.describeTopic("test.lifecycle"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void rejectsProtectedTopicsInvalidConfigsAndExcessiveReplication() {
        assertThatThrownBy(() -> kafkaAdminService.deleteTopic("__consumer_offsets"))
                .isInstanceOf(ProtectedResourceException.class);
        assertThatThrownBy(() -> kafkaAdminService.createTopic(
                new TopicCreateRequest("test.rf", 1, (short) 3, Map.of())))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("brokers disponibles");
        assertThatThrownBy(() -> kafkaAdminService.createTopic(
                new TopicCreateRequest("test.cfg", 1, (short) 1, Map.of("log.dirs", "/tmp"))))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("no permitidas");
        kafkaAdminService.createTopic(new TopicCreateRequest("test.duplicated", 1, (short) 1, Map.of()));
        assertThatThrownBy(() -> kafkaAdminService.createTopic(
                new TopicCreateRequest("test.duplicated", 1, (short) 1, Map.of())))
                .isInstanceOf(ResourceConflictException.class);
        assertThatThrownBy(() -> kafkaAdminService.increasePartitions("test.duplicated", 1))
                .isInstanceOf(InvalidConfigurationException.class);
    }

    @Test
    void consumerGroupLagAndOffsetReset() {
        kafkaAdminService.createTopic(new TopicCreateRequest("test.lag", 1, (short) 1, Map.of()));
        produce("test.lag", 5);
        // Un consumidor del grupo lee y confirma solo 2 de los 5 mensajes -> lag 3.
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ConsumerConfig.GROUP_ID_CONFIG, "test-group",
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false,
                ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 2,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            consumer.subscribe(List.of("test.lag"));
            int read = 0;
            long deadline = System.currentTimeMillis() + 15_000;
            while (read == 0 && System.currentTimeMillis() < deadline) {
                read = consumer.poll(Duration.ofMillis(500)).count();
            }
            consumer.commitSync();
        }

        ConsumerGroupDetail detail = kafkaAdminService.describeConsumerGroup("test-group");
        assertThat(detail.totalLag()).isEqualTo(3);
        assertThat(kafkaAdminService.listConsumerGroups())
                .anySatisfy(group -> assertThat(group.groupId()).isEqualTo("test-group"));

        ConsumerGroupDetail latest = kafkaAdminService.resetOffsets("test-group",
                new OffsetResetRequest("test.lag", OffsetResetRequest.Strategy.LATEST, null, null));
        assertThat(latest.totalLag()).isZero();
        ConsumerGroupDetail earliest = kafkaAdminService.resetOffsets("test-group",
                new OffsetResetRequest("test.lag", OffsetResetRequest.Strategy.EARLIEST, null, null));
        assertThat(earliest.totalLag()).isEqualTo(5);

        kafkaAdminService.deleteConsumerGroup("test-group");
        assertThatThrownBy(() -> kafkaAdminService.describeConsumerGroup("test-group"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deadLetterSummaryPeekAndReprocess() {
        kafkaAdminService.createTopic(new TopicCreateRequest("test.orders", 1, (short) 1, Map.of()));
        kafkaAdminService.createTopic(new TopicCreateRequest("test.orders.DLT", 1, (short) 1, Map.of()));
        try (KafkaProducer<String, String> producer = producer()) {
            ProducerRecord<String, String> failed = new ProducerRecord<>("test.orders.DLT", "order-1", "{\"bad\":true}");
            failed.headers().add("kafka_dlt-exception-message", "payload invalido".getBytes(StandardCharsets.UTF_8));
            producer.send(failed);
            producer.flush();
        }

        DeadLetterSummary before = deadLetterService.summary();
        assertThat(before.alert()).isTrue();
        assertThat(before.topics()).anySatisfy(topic -> {
            assertThat(topic.topic()).isEqualTo("test.orders.DLT");
            assertThat(topic.sourceTopic()).isEqualTo("test.orders");
            assertThat(topic.messages()).isEqualTo(1);
        });
        assertThat(deadLetterService.peek("test.orders.DLT", 5)).singleElement().satisfies(message -> {
            assertThat(message.key()).isEqualTo("order-1");
            assertThat(message.headers()).containsEntry("kafka_dlt-exception-message", "payload invalido");
        });

        ReprocessResult result = deadLetterService.reprocess("test.orders.DLT", null, null);
        assertThat(result.republished()).isEqualTo(1);
        assertThat(result.targetTopic()).isEqualTo("test.orders");
        assertThat(kafkaAdminService.describeTopic("test.orders").totalMessages()).isEqualTo(1);
        assertThat(deadLetterService.summary().topics())
                .filteredOn(topic -> topic.topic().equals("test.orders.DLT"))
                .singleElement()
                .satisfies(topic -> assertThat(topic.messages()).isZero());
        assertThatThrownBy(() -> deadLetterService.peek("test.orders", 5))
                .isInstanceOf(InvalidConfigurationException.class);
    }

    private void produce(String topic, int count) {
        try (KafkaProducer<String, String> producer = producer()) {
            for (int i = 0; i < count; i++) {
                producer.send(new ProducerRecord<>(topic, "k" + i, "v" + i));
            }
            producer.flush();
        }
    }

    private KafkaProducer<String, String> producer() {
        return new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, brokers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
    }
}
