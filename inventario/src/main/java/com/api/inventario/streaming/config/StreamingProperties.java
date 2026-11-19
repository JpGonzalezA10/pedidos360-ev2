package com.api.inventario.streaming.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * EP5 - Configuracion centralizada de Kafka del microservicio inventario (prefijo "streaming").
 * inventario produce pedidos360.inventario.stock y consume pedidos360.pedidos.eventos.
 */
@Validated
@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(
        @Valid @NotNull Topics topics,
        @Valid @NotNull ConsumerGroups consumerGroups,
        @Valid @NotNull DeadLetter deadLetter,
        @Valid @NotNull Retry retry) {

    /** Topico propio (stock) y topico ajeno que se consume (eventos de pedidos). */
    public record Topics(
            @Valid @NotNull TopicSpec inventarioStock,
            @Valid @NotNull TopicSpec pedidosEventos) {
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

    /** Consumer group que calcula la demanda por SKU a partir del stream de pedidos. */
    public record ConsumerGroups(@NotBlank String demanda) {
    }

    /** Dead Letter Topics: sufijo, replicas y configuracion. */
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

    /** Reintentos bloqueantes con backoff exponencial antes de enviar a la DLT. */
    public record Retry(
            @Min(0) int maxRetries,
            @Min(100) long initialIntervalMs,
            @DecimalMin("1.0") double multiplier,
            @Min(100) long maxIntervalMs) {
    }
}
