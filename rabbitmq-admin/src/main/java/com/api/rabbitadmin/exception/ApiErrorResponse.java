package com.api.rabbitadmin.exception;

import java.time.OffsetDateTime;
import java.util.Map;

/*
 * Contrato comun para errores HTTP del microservicio.
 */
public record ApiErrorResponse(
        OffsetDateTime timestamp,
        int status,
        String error,
        String message,
        String path,
        Map<String, String> details) {
}
