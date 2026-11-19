package com.api.kafkaadmin.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * Configuracion centralizada del administrador de Kafka (prefijo "kafka-admin").
 *  - protectedPrefixes: topicos internos (__consumer_offsets, _schemas...) que la API nunca modifica ni elimina.
 *  - allowedTopicConfigs: claves de configuracion de topico que la API permite definir o modificar.
 *  - deadLetterSuffix: sufijo que identifica las DLT para el monitoreo y el reprocesamiento.
 */
@Validated
@ConfigurationProperties(prefix = "kafka-admin")
public record KafkaAdminProperties(
        @NotEmpty List<String> protectedPrefixes,
        @NotEmpty Set<String> allowedTopicConfigs,
        @NotBlank String deadLetterSuffix,
        @Min(1000) long operationTimeoutMs,
        @Min(1) @Max(32767) short maxReplicationFactor,
        @Min(1) @Max(1000) int maxPartitions,
        @Valid @NotNull Reprocess reprocess) {

    /** Reprocesamiento de DLT: consumer group propio y lote maximo por invocacion. */
    public record Reprocess(
            @NotBlank String groupId,
            @Min(1) @Max(10_000) int maxMessages,
            @Min(100) long pollTimeoutMs) {
    }

    public boolean isProtected(String resourceName) {
        return protectedPrefixes.stream().anyMatch(resourceName::startsWith);
    }
}
