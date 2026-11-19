package com.api.envios.streaming.config;

import com.api.envios.streaming.support.DeadLetterValueSerializer;
import com.api.envios.streaming.support.KafkaHeadersNames;
import com.api.envios.streaming.support.NonRetryableEventException;
import java.nio.charset.StandardCharsets;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.annotation.KafkaListenerConfigurer;
import org.springframework.kafka.config.KafkaListenerEndpointRegistrar;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.RecordInterceptor;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;
import org.springframework.validation.Validator;

/*
 * EP5 - Manejo de errores de los consumidores Kafka (guia 3.3.3).
 *
 * 1. Deserializacion: ErrorHandlingDeserializer (application.properties) convierte un JSON corrupto
 *    en DeserializationException en vez de dejar al consumidor en un bucle infinito.
 * 2. Validacion: los @KafkaListener usan @Valid sobre el DTO con el Validator de Spring (JSR-380).
 * 3. Reintentos: DefaultErrorHandler con backoff exponencial (1 s, 2 s, 4 s) para errores transitorios.
 * 4. DLT: agotados los reintentos, o ante un error permanente, DeadLetterPublishingRecoverer publica
 *    el mensaje original en <topico>.DLT (misma particion) con headers de la excepcion.
 * 5. Logging contextual: un RecordInterceptor copia el correlation-id del header Kafka al MDC.
 */
@Configuration
public class KafkaErrorHandlingConfig implements KafkaListenerConfigurer {

    private static final Logger log = LoggerFactory.getLogger(KafkaErrorHandlingConfig.class);

    private final Validator validator;

    public KafkaErrorHandlingConfig(Validator validator) {
        this.validator = validator;
    }

    /** Habilita @Valid en los parametros @Payload de los @KafkaListener. */
    @Override
    public void configureKafkaListeners(KafkaListenerEndpointRegistrar registrar) {
        registrar.setValidator(validator);
    }

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            ProducerFactory<?, ?> producerFactory, StreamingProperties properties) {
        // Template exclusivo de la DLT (no se expone como bean para no reemplazar el KafkaTemplate de Spring Boot).
        // Fallo de deserializacion -> el valor llega como byte[] y se reenvia tal cual;
        // fallo de validacion/negocio -> llega como DTO y se serializa a JSON.
        KafkaTemplate<Object, Object> deadLetterKafkaTemplate = new KafkaTemplate<>(
                new DefaultKafkaProducerFactory<>(producerFactory.getConfigurationProperties(),
                        new DeadLetterValueSerializer(), new DeadLetterValueSerializer()));
        StreamingProperties.DeadLetter dlt = properties.deadLetter();
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(
                deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(dlt.topicFor(record.topic()), record.partition()));

        StreamingProperties.Retry retry = properties.retry();
        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(retry.maxRetries());
        backOff.setInitialInterval(retry.initialIntervalMs());
        backOff.setMultiplier(retry.multiplier());
        backOff.setMaxInterval(retry.maxIntervalMs());

        DefaultErrorHandler handler = new DefaultErrorHandler((record, exception) -> {
            log.error("[DLT] Fallo definitivo topic={} partition={} offset={} key={} -> {}: {}",
                    record.topic(), record.partition(), record.offset(), record.key(),
                    dlt.topicFor(record.topic()), rootMessage(exception));
            recoverer.accept(record, exception);
        }, backOff);
        // Errores permanentes: no tiene sentido reintentar, van directo a la DLT.
        // (DeserializationException y MethodArgumentNotValidException ya son no reintentables por defecto.)
        handler.addNotRetryableExceptions(NonRetryableEventException.class, IllegalArgumentException.class);
        handler.setRetryListeners((record, exception, deliveryAttempt) -> log.warn(
                "[KAFKA-FALLO] topic={} partition={} offset={} intento {} (max {}): {}",
                record.topic(), record.partition(), record.offset(),
                deliveryAttempt, retry.maxRetries() + 1, rootMessage(exception)));
        return handler;
    }

    /** Correlation-id del productor -> MDC, para que cada linea de log del consumidor lo incluya. */
    @Bean
    public RecordInterceptor<Object, Object> correlationIdRecordInterceptor() {
        return new RecordInterceptor<>() {
            @Override
            public ConsumerRecord<Object, Object> intercept(
                    ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                Header header = record.headers().lastHeader(KafkaHeadersNames.CORRELATION_ID);
                if (header != null) {
                    MDC.put(KafkaHeadersNames.MDC_CORRELATION_ID, new String(header.value(), StandardCharsets.UTF_8));
                }
                MDC.put(KafkaHeadersNames.MDC_RECORD,
                        record.topic() + "-" + record.partition() + "@" + record.offset());
                return record;
            }

            @Override
            public void afterRecord(ConsumerRecord<Object, Object> record, Consumer<Object, Object> consumer) {
                MDC.remove(KafkaHeadersNames.MDC_CORRELATION_ID);
                MDC.remove(KafkaHeadersNames.MDC_RECORD);
            }
        };
    }

    private static String rootMessage(Throwable exception) {
        Throwable root = exception;
        while (root.getCause() != null && root.getCause() != root) {
            root = root.getCause();
        }
        return root.getClass().getSimpleName() + ": " + root.getMessage();
    }
}
