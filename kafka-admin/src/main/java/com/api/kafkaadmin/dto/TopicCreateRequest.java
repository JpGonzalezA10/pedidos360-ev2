package com.api.kafkaadmin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Map;

/*
 * Solicitud de creacion de topico.
 * Nombre: letras, numeros, punto, guion y guion bajo (regla de Kafka), maximo 249 caracteres.
 * Las configuraciones (retention.ms, cleanup.policy, min.insync.replicas...) se validan contra la
 * lista permitida en KafkaAdminProperties y luego el broker valida sus valores.
 */
public record TopicCreateRequest(
        @NotBlank
        @Size(max = 249)
        @Pattern(regexp = "[a-zA-Z0-9._-]+", message = "solo letras, numeros, '.', '_' y '-'")
        String name,
        @Min(1) @Max(1000) int partitions,
        @Min(1) @Max(32767) short replicationFactor,
        Map<String, String> configs) {

    public Map<String, String> safeConfigs() {
        return configs == null ? Map.of() : configs;
    }
}
