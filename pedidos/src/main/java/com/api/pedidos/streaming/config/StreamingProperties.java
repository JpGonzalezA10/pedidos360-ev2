package com.api.pedidos.streaming.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * EP5 - Configuracion centralizada de Kafka del microservicio pedidos (prefijo "streaming").
 * Nombres de topicos, particiones, factor de replicacion, configuraciones de topico
 * (retencion, cleanup.policy, min.insync.replicas), consumer groups y politica de reintentos.
 * Ninguna clase de negocio ni listener escribe nombres de topicos a mano.
 */
@Validated
@ConfigurationProperties(prefix = "streaming")
public record StreamingProperties(
        @Valid @NotNull Topics topics,
        @Valid @NotNull ConsumerGroups consumerGroups,
        @Valid @NotNull DeadLetter deadLetter,
        @Valid @NotNull Retry retry) {

    /** Topico propio (pedidos produce) y topico ajeno que pedidos consume. */
    public record Topics(
            @Valid @NotNull TopicSpec pedidosEventos,
            @Valid @NotNull TopicSpec inventarioStock) {
    }

    /**
     * Definicion de un topico: nombre, particiones (paralelismo maximo del consumer group),
     * replicas (tolerancia a fallos de brokers) y configuraciones propias del topico.
     */
    public record TopicSpec(
            @NotBlank String name,
            @Min(1) int partitions,
            @Min(1) short replicas,
            Map<String, String> configs) {

        public TopicSpec {
            configs = configs == null ? Map.of() : Map.copyOf(configs);
        }
    }

    /** Consumer groups de este microservicio. */
    public record ConsumerGroups(@NotBlank String stockCache) {
    }

    /** Dead Letter Topics: sufijo, replicas y configuracion (retencion mas larga para auditoria). */
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
