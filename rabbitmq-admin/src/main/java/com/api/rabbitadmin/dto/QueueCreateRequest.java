package com.api.rabbitadmin.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

/*
 * Solicitud para crear una cola.
 * Ejemplo: {"name":"notificaciones.queue","durable":true,"messageTtlMs":60000,
 *           "maxLength":1000,"deadLetterExchange":"notificaciones.dlx","deadLetterRoutingKey":"notificaciones.dlq"}
 */
public record QueueCreateRequest(
        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = ValidationPatterns.RESOURCE_NAME, message = ValidationPatterns.RESOURCE_NAME_MESSAGE)
        String name,

        // Si no se informa, la cola se crea durable (sobrevive reinicios del broker).
        Boolean durable,

        // TTL de los mensajes en milisegundos (1 ms a 30 dias).
        @Positive(message = "debe ser mayor que cero")
        @Max(value = 2_592_000_000L, message = "no puede superar 30 dias (2592000000 ms)")
        Long messageTtlMs,

        // Largo maximo de la cola (politica de retencion).
        @Positive(message = "debe ser mayor que cero")
        @Max(value = 1_000_000, message = "no puede superar 1.000.000 mensajes")
        Integer maxLength,

        @Pattern(regexp = ValidationPatterns.RESOURCE_NAME, message = ValidationPatterns.RESOURCE_NAME_MESSAGE)
        String deadLetterExchange,

        @Pattern(regexp = ValidationPatterns.ROUTING_KEY, message = ValidationPatterns.ROUTING_KEY_MESSAGE)
        String deadLetterRoutingKey) {

    public boolean isDurableOrDefault() {
        return durable == null || durable;
    }

    /** Una dead-letter-routing-key sin dead-letter-exchange es una configuracion invalida. */
    @AssertTrue(message = "deadLetterRoutingKey requiere informar deadLetterExchange")
    public boolean isDeadLetterConfigurationConsistent() {
        return deadLetterRoutingKey == null || deadLetterRoutingKey.isBlank()
                || (deadLetterExchange != null && !deadLetterExchange.isBlank());
    }
}
