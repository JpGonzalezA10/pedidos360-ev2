package com.api.rabbitadmin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/*
 * Solicitud para crear o eliminar un binding exchange -> cola.
 * Ejemplo: {"exchange":"pedidos.events","queue":"notificaciones.queue","routingKey":"pedido.#"}
 */
public record BindingRequest(
        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = ValidationPatterns.RESOURCE_NAME, message = ValidationPatterns.RESOURCE_NAME_MESSAGE)
        String exchange,

        @NotBlank(message = "es obligatorio")
        @Pattern(regexp = ValidationPatterns.RESOURCE_NAME, message = ValidationPatterns.RESOURCE_NAME_MESSAGE)
        String queue,

        // Vacia es valida (exchanges fanout ignoran la routing key).
        @Pattern(regexp = ValidationPatterns.ROUTING_KEY, message = ValidationPatterns.ROUTING_KEY_MESSAGE)
        String routingKey) {

    public String routingKeyOrEmpty() {
        return routingKey == null ? "" : routingKey.trim();
    }
}
