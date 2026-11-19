package com.api.kafkaadmin.service;

import com.api.kafkaadmin.config.KafkaAdminProperties;
import com.api.kafkaadmin.exception.BrokerUnavailableException;
import com.api.kafkaadmin.exception.InvalidConfigurationException;
import com.api.kafkaadmin.exception.ResourceConflictException;
import com.api.kafkaadmin.exception.ResourceNotFoundException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.errors.ApiException;
import org.apache.kafka.common.errors.GroupIdNotFoundException;
import org.apache.kafka.common.errors.GroupNotEmptyException;
import org.apache.kafka.common.errors.InvalidPartitionsException;
import org.apache.kafka.common.errors.InvalidReplicationFactorException;
import org.apache.kafka.common.errors.InvalidRequestException;
import org.apache.kafka.common.errors.InvalidTopicException;
import org.apache.kafka.common.errors.PolicyViolationException;
import org.apache.kafka.common.errors.RetriableException;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.errors.UnknownMemberIdException;
import org.apache.kafka.common.errors.UnknownTopicOrPartitionException;
import org.springframework.stereotype.Component;

/*
 * Espera las operaciones asincronas del AdminClient con timeout y traduce los errores de Kafka
 * a las excepciones HTTP del microservicio (404, 409, 422, 503).
 */
@Component
public class KafkaAdminOperations {

    private final long timeoutMs;

    public KafkaAdminOperations(KafkaAdminProperties properties) {
        this.timeoutMs = properties.operationTimeoutMs();
    }

    public <T> T await(KafkaFuture<T> future) {
        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new BrokerUnavailableException("Operacion sobre Kafka interrumpida", exception);
        } catch (TimeoutException exception) {
            throw new BrokerUnavailableException("Kafka no respondio en " + timeoutMs + " ms", exception);
        } catch (ExecutionException exception) {
            throw translate(exception.getCause());
        }
    }

    RuntimeException translate(Throwable cause) {
        String message = cause.getMessage();
        if (cause instanceof UnknownTopicOrPartitionException) {
            return new ResourceNotFoundException("El topico o particion no existe: " + message);
        }
        if (cause instanceof GroupIdNotFoundException) {
            return new ResourceNotFoundException("El consumer group no existe: " + message);
        }
        if (cause instanceof TopicExistsException) {
            return new ResourceConflictException("El topico ya existe: " + message, cause);
        }
        if (cause instanceof GroupNotEmptyException || cause instanceof UnknownMemberIdException) {
            return new ResourceConflictException(
                    "El consumer group tiene consumidores activos; detengalos antes de esta operacion", cause);
        }
        if (cause instanceof InvalidReplicationFactorException
                || cause instanceof InvalidPartitionsException
                || cause instanceof InvalidTopicException
                || cause instanceof InvalidRequestException
                || cause instanceof PolicyViolationException
                || cause instanceof org.apache.kafka.common.errors.InvalidConfigurationException) {
            return new InvalidConfigurationException("Kafka rechazo la configuracion: " + message);
        }
        if (cause instanceof RetriableException) {
            return new BrokerUnavailableException("Kafka no disponible: " + message, cause);
        }
        if (cause instanceof ApiException) {
            return new InvalidConfigurationException("Kafka rechazo la operacion: " + message);
        }
        return new BrokerUnavailableException("Error de comunicacion con Kafka: " + message, cause);
    }
}
