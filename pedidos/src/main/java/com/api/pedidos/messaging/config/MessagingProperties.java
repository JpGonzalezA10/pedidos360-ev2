package com.api.pedidos.messaging.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * Nombres centralizados de la topologia RabbitMQ del microservicio pedidos.
 * Todos los valores se definen en application.properties (prefijo "messaging");
 * ninguna clase de negocio ni consumidor escribe nombres de colas a mano.
 */
@Validated
@ConfigurationProperties(prefix = "messaging")
public record MessagingProperties(
        @Valid @NotNull Exchanges exchanges,
        @Valid @NotNull RoutingKeys routingKeys,
        @Valid @NotNull Queues queues,
        @Valid @NotNull Retry retry,
        @Valid @NotNull Dlq dlq) {

    /** Exchanges propios (pedidos.events), ajenos que se escuchan (envios.events) y DLX. */
    public record Exchanges(
            @NotBlank String pedidos,
            @NotBlank String envios,
            @NotBlank String deadLetter) {
    }

    /** Routing keys de los eventos publicados y consumidos. */
    public record RoutingKeys(
            @NotBlank String pedidoConfirmado,
            @NotBlank String pedidoCancelado,
            @NotBlank String pedidoEstadoActualizado,
            @NotBlank String envioEstadoActualizado) {
    }

    /** Cola principal, cola de reintento diferido y DLQ de cada caso de uso consumido. */
    public record Queues(
            @NotBlank String envioEstado,
            @NotBlank String envioEstadoRetry,
            @NotBlank String envioEstadoDlq,
            @NotBlank String notificacionesCliente,
            @NotBlank String notificacionesClienteRetry,
            @NotBlank String notificacionesClienteDlq) {
    }

    /** Politica de reintentos: intentos maximos y espera (TTL de la cola retry). */
    public record Retry(
            @Min(1) int maxAttempts,
            @Min(100) long delayMs) {
    }

    /** Politica de retencion de la DLQ: tiempo maximo y cantidad maxima de mensajes. */
    public record Dlq(
            @Min(1000) long retentionMs,
            @Min(1) int maxLength) {
    }
}
