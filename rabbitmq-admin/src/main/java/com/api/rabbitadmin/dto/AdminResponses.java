package com.api.rabbitadmin.dto;

import java.util.List;
import java.util.Map;

/*
 * DTO de salida del administrador. Se agrupan como records anidados porque son
 * contratos simples de lectura que el frontend consume directamente.
 */
public final class AdminResponses {

    private AdminResponses() {
    }

    /** Estado de una cola: mensajes listos, sin confirmar, consumidores y argumentos (DLX, TTL...). */
    public record QueueResponse(
            String name,
            boolean durable,
            long messagesReady,
            long messagesUnacknowledged,
            long messages,
            int consumers,
            Map<String, Object> arguments) {
    }

    public record ExchangeResponse(
            String name,
            String type,
            boolean durable,
            boolean internal) {
    }

    public record BindingResponse(
            String exchange,
            String destination,
            String destinationType,
            String routingKey) {
    }

    /** Resultado de una operacion de escritura (crear, eliminar, purgar). */
    public record OperationResponse(
            String message,
            String resource,
            Long affectedMessages) {
    }

    /** Mensaje inspeccionado en una cola (se devuelve a la cola, no se consume). */
    public record QueuedMessageResponse(
            String exchange,
            String routingKey,
            boolean redelivered,
            String payload,
            Map<String, Object> headers) {
    }

    public record DeadLetterQueueStatus(
            String name,
            long messages,
            int consumers) {
    }

    /** Resumen para alertas: total de mensajes en todas las DLQ y si corresponde alertar. */
    public record DeadLetterSummaryResponse(
            long totalMessages,
            boolean alert,
            List<DeadLetterQueueStatus> queues) {
    }
}
