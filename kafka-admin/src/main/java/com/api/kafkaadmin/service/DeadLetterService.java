package com.api.kafkaadmin.service;

import com.api.kafkaadmin.config.KafkaAdminProperties;
import com.api.kafkaadmin.dto.AdminResponses.DeadLetterMessage;
import com.api.kafkaadmin.dto.AdminResponses.DeadLetterSummary;
import com.api.kafkaadmin.dto.AdminResponses.DeadLetterTopic;
import com.api.kafkaadmin.dto.AdminResponses.ReprocessResult;
import com.api.kafkaadmin.exception.InvalidConfigurationException;
import com.api.kafkaadmin.exception.BrokerUnavailableException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListTopicsOptions;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Service;

/*
 * Monitoreo y reprocesamiento de Dead Letter Topics (<topico>.DLT), guia 3.3.3:
 *  - Resumen: mensajes pendientes por DLT (alerta si hay alguno sin reprocesar).
 *  - Inspeccion: ultimos mensajes con los headers de error que agrega DeadLetterPublishingRecoverer.
 *  - Reprocesamiento: re-inyecta los mensajes en el topico original una vez corregida la causa. Usa un
 *    consumer group propio para no reprocesar dos veces el mismo mensaje.
 */
@Service
public class DeadLetterService {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);
    private static final int MAX_HEADER_LENGTH = 300;

    private final AdminClient admin;
    private final KafkaAdminService kafkaAdminService;
    private final KafkaAdminProperties properties;
    private final KafkaAdminOperations operations;
    private final Object bootstrapServers;

    public DeadLetterService(
            AdminClient admin,
            KafkaAdminService kafkaAdminService,
            KafkaAdminProperties properties,
            KafkaAdminOperations operations,
            KafkaAdmin kafkaAdmin) {
        this.admin = admin;
        this.kafkaAdminService = kafkaAdminService;
        this.properties = properties;
        this.operations = operations;
        this.bootstrapServers = kafkaAdmin.getConfigurationProperties().get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
    }

    /** Mensajes pendientes por DLT = fin del topico - offset ya reprocesado (o inicio retenido). */
    public DeadLetterSummary summary() {
        Set<String> names = operations.await(admin.listTopics(new ListTopicsOptions()).names());
        Map<TopicPartition, OffsetAndMetadata> reprocessed = kafkaAdminService.committedOffsets(
                properties.reprocess().groupId());
        List<DeadLetterTopic> topics = new ArrayList<>();
        for (String name : names.stream().filter(this::isDeadLetterTopic).sorted().toList()) {
            List<TopicPartition> partitions = partitions(name);
            Map<TopicPartition, Long> begin = kafkaAdminService.offsets(partitions, OffsetSpec.earliest());
            Map<TopicPartition, Long> end = kafkaAdminService.offsets(partitions, OffsetSpec.latest());
            long pending = partitions.stream().mapToLong(tp -> {
                long from = Math.max(begin.getOrDefault(tp, 0L),
                        reprocessed.containsKey(tp) ? reprocessed.get(tp).offset() : 0L);
                return Math.max(0, end.getOrDefault(tp, 0L) - from);
            }).sum();
            topics.add(new DeadLetterTopic(name, sourceTopic(name), pending));
        }
        long total = topics.stream().mapToLong(DeadLetterTopic::messages).sum();
        return new DeadLetterSummary(total > 0, total, topics);
    }

    /** Ultimos mensajes de una DLT (sin consumirlos: no usa consumer group). */
    public List<DeadLetterMessage> peek(String deadLetterTopic, int limit) {
        assertDeadLetterTopic(deadLetterTopic);
        List<TopicPartition> partitions = partitions(deadLetterTopic);
        Map<TopicPartition, Long> begin = kafkaAdminService.offsets(partitions, OffsetSpec.earliest());
        Map<TopicPartition, Long> end = kafkaAdminService.offsets(partitions, OffsetSpec.latest());
        List<DeadLetterMessage> messages = new ArrayList<>();
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps(null))) {
            consumer.assign(partitions);
            long remaining = 0;
            for (TopicPartition tp : partitions) {
                long from = Math.max(begin.get(tp), end.get(tp) - limit);
                consumer.seek(tp, from);
                remaining += end.get(tp) - from;
            }
            long deadline = System.currentTimeMillis() + properties.reprocess().pollTimeoutMs() * 3;
            while (remaining > 0 && System.currentTimeMillis() < deadline) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(properties.reprocess().pollTimeoutMs()));
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    messages.add(toMessage(record));
                    remaining--;
                }
            }
        }
        return messages.stream()
                .sorted(Comparator.comparingLong(DeadLetterMessage::timestamp).reversed())
                .limit(limit)
                .toList();
    }

    /**
     * Re-inyecta hasta maxMessages mensajes de la DLT en su topico original (o en targetTopic).
     * Los headers de error kafka_dlt-* no se copian; se agrega X-Reprocessed-From para trazabilidad.
     */
    public ReprocessResult reprocess(String deadLetterTopic, Integer maxMessages, String targetTopic) {
        assertDeadLetterTopic(deadLetterTopic);
        String target = targetTopic == null || targetTopic.isBlank() ? sourceTopic(deadLetterTopic) : targetTopic;
        kafkaAdminService.describeTopicDescription(target);
        int limit = maxMessages == null ? properties.reprocess().maxMessages()
                : Math.min(maxMessages, properties.reprocess().maxMessages());
        String groupId = properties.reprocess().groupId();
        int republished = 0;
        try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(consumerProps(groupId));
                KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(producerProps())) {
            consumer.assign(partitions(deadLetterTopic));
            boolean keepReading = true;
            while (keepReading && republished < limit) {
                ConsumerRecords<byte[], byte[]> records = consumer.poll(Duration.ofMillis(properties.reprocess().pollTimeoutMs()));
                keepReading = !records.isEmpty();
                Map<TopicPartition, OffsetAndMetadata> toCommit = new HashMap<>();
                for (ConsumerRecord<byte[], byte[]> record : records) {
                    if (republished >= limit) {
                        break;
                    }
                    ProducerRecord<byte[], byte[]> copy = new ProducerRecord<>(target, record.key(), record.value());
                    for (Header header : record.headers()) {
                        if (!header.key().startsWith("kafka_dlt-")) {
                            copy.headers().add(header);
                        }
                    }
                    copy.headers().add("X-Reprocessed-From",
                            (record.topic() + "-" + record.partition() + "@" + record.offset()).getBytes(StandardCharsets.UTF_8));
                    producer.send(copy).get(properties.operationTimeoutMs(), TimeUnit.MILLISECONDS);
                    toCommit.put(new TopicPartition(record.topic(), record.partition()),
                            new OffsetAndMetadata(record.offset() + 1));
                    republished++;
                }
                if (!toCommit.isEmpty()) {
                    consumer.commitSync(toCommit);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("Reprocesamiento interrumpido", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new BrokerUnavailableException("No se pudo re-publicar en " + target + ": " + exception.getMessage(),
                    exception);
        }
        log.info("[KAFKA-ADMIN] Reprocesados {} mensajes de {} hacia {}", republished, deadLetterTopic, target);
        return new ReprocessResult(deadLetterTopic, target, republished, groupId);
    }

    private DeadLetterMessage toMessage(ConsumerRecord<byte[], byte[]> record) {
        Map<String, String> headers = new LinkedHashMap<>();
        for (Header header : record.headers()) {
            headers.put(header.key(), headerValue(header));
        }
        return new DeadLetterMessage(record.partition(), record.offset(), text(record.key()), text(record.value()),
                record.timestamp(), headers);
    }

    /** Los headers de particion/offset original son binarios (int/long); el resto es texto. */
    private String headerValue(Header header) {
        byte[] value = header.value();
        if (value == null) {
            return null;
        }
        if (KafkaHeaders.DLT_ORIGINAL_PARTITION.equals(header.key()) && value.length == Integer.BYTES) {
            return String.valueOf(ByteBuffer.wrap(value).getInt());
        }
        if ((KafkaHeaders.DLT_ORIGINAL_OFFSET.equals(header.key())
                || KafkaHeaders.DLT_ORIGINAL_TIMESTAMP.equals(header.key())) && value.length == Long.BYTES) {
            return String.valueOf(ByteBuffer.wrap(value).getLong());
        }
        String text = new String(value, StandardCharsets.UTF_8);
        return text.length() > MAX_HEADER_LENGTH ? text.substring(0, MAX_HEADER_LENGTH) + "..." : text;
    }

    private List<TopicPartition> partitions(String topic) {
        return kafkaAdminService.describeTopicDescription(topic).partitions().stream()
                .map(info -> new TopicPartition(topic, info.partition()))
                .toList();
    }

    private boolean isDeadLetterTopic(String name) {
        return name.endsWith(properties.deadLetterSuffix());
    }

    private String sourceTopic(String deadLetterTopic) {
        return deadLetterTopic.substring(0, deadLetterTopic.length() - properties.deadLetterSuffix().length());
    }

    private void assertDeadLetterTopic(String name) {
        if (!isDeadLetterTopic(name)) {
            throw new InvalidConfigurationException("'" + name + "' no es una DLT (sufijo " + properties.deadLetterSuffix() + ")");
        }
    }

    private Map<String, Object> consumerProps(String groupId) {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.CLIENT_ID_CONFIG, "kafka-admin-dlt");
        if (groupId != null) {
            props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        }
        return props;
    }

    private Map<String, Object> producerProps() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "kafka-admin-reprocessor");
        return props;
    }

    private static String text(byte[] bytes) {
        return bytes == null ? null : new String(bytes, StandardCharsets.UTF_8);
    }
}
