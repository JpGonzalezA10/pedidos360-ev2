package com.api.rabbitadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/*
 * Solicitud para crear un exchange.
 * Ejemplo: {"name":"notificaciones.events","type":"topic","durable":true}
 */
public record ExchangeCreateRequest(
        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = ValidationPatterns.RESOURCE_NAME, message = ValidationPatterns.RESOURCE_NAME_MESSAGE)
        String name,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = ValidationPatterns.EXCHANGE_TYPE, message = "debe ser direct, topic, fanout o headers")
        String type,

        Boolean durable) {

    public boolean isDurableOrDefault() {
        return durable == null || durable;
    }
}
