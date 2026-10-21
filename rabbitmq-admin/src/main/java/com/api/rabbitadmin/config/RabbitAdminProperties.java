package com.api.rabbitadmin.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/*
 * Configuracion centralizada del administrador (prefijo "rabbit-admin" en application.properties).
 *  - management: acceso a la Management HTTP API de RabbitMQ (listados y estadisticas).
 *  - protectedPrefixes: prefijos de recursos que la API nunca crea ni elimina (ej. amq.*).
 *  - deadLetterSuffix: sufijo con el que se identifican las DLQ para el monitoreo.
 */
@Validated
@ConfigurationProperties(prefix = "rabbit-admin")
public record RabbitAdminProperties(
        @Valid @NotNull Management management,
        @NotNull List<String> protectedPrefixes,
        @NotBlank String deadLetterSuffix) {

    public record Management(
            @NotBlank String url,
            @NotBlank String username,
            @NotBlank String password,
            @NotBlank String virtualHost) {
    }
}
