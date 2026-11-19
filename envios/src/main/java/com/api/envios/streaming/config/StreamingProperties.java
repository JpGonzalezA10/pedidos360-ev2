package com.api.envios.streaming.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * EP5 - Configuracion centralizada de Kafka del microservicio envios (prefijo "streaming").
 * envios consume pedidos360.pedidos.eventos con su propio consumer group (envios-seguimiento).
 */
@Validated
@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(
        @Valid @NotNull Topics topics,
        @Valid @NotNull ConsumerGroups consumerGroups,
        @Valid @NotNull DeadLetter deadLetter,
        @Valid @NotNull Retry retry) {

    public record Topics(@Valid @NotNull TopicSpec pedidosEventos) {
    }

    /** Nombre, particiones, replicas y configuraciones de un topico. */
    public record TopicSpec(
            @NotBlank String name,
            @Min(1) int partitions,
            @Min(1) short replicas,
            Map<String, String> configs) {

        public TopicSpec {
            configs = configs == null ? Map.of() : Map.copyOf(configs);
        }
    }

    /** Consumer group que sigue el ciclo de vida de pedidos para la operacion de despacho. */
    public record ConsumerGroups(@NotBlank String seguimiento) {
    }

    public record DeadLetter(
            @NotBlank String suffix,
            @Min(1) short replicas,
            Map<String, String> configs) {

        public DeadLetter {
            configs = configs == null ? Map.of() : Map.copyOf(configs);
        }

        public String topicFor(String sourceTopic) {
            return sourceTopic + suffix;
        }
    }

    public record Retry(
            @Min(0) int maxRetries,
            @Min(100) long initialIntervalMs,
            @DecimalMin("1.0") double multiplier,
            @Min(100) long maxIntervalMs) {
    }
}
