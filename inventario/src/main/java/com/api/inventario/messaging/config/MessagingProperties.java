package com.api.inventario.messaging.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * Nombres centralizados de la topologia RabbitMQ del microservicio inventario.
 * Todos los valores se definen en application.properties (prefijo "messaging").
 */
@Validated
@ConfigurationProperties(prefix = "messaging")
public record MessagingProperties(
        @Valid @NotNull Exchanges exchanges,
        @Valid @NotNull RoutingKeys routingKeys,
        @Valid @NotNull Queues queues,
        @Valid @NotNull Retry retry,
        @Valid @NotNull Dlq dlq) {

    /** Exchange propio (inventario.events), exchange escuchado (pedidos.events) y DLX. */
    public record Exchanges(
            @NotBlank String inventario,
            @NotBlank String pedidos,
            @NotBlank String deadLetter) {
    }

    /** Routing keys publicadas (inventario.*) y consumidas (pedido.*). */
    public record RoutingKeys(
            @NotBlank String stockBajo,
            @NotBlank String pedidoConfirmado) {
    }

    /** Colas principal, retry y DLQ de cada caso de uso consumido. */
    public record Queues(
            @NotBlank String pedidoConfirmado,
            @NotBlank String pedidoConfirmadoRetry,
            @NotBlank String pedidoConfirmadoDlq,
            @NotBlank String alertasStock,
            @NotBlank String alertasStockRetry,
            @NotBlank String alertasStockDlq) {
    }

    /** Politica de reintentos: intentos maximos y espera (TTL de la cola retry). */
    public record Retry(
            @Min(1) int maxAttempts,
            @Min(100) long delayMs) {
    }

    /** Politica de retencion de las DLQ: tiempo maximo y cantidad maxima de mensajes. */
    public record Dlq(
            @Min(1000) long retentionMs,
            @Min(1) int maxLength) {
    }
}
