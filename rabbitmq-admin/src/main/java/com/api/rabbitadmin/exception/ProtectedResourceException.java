package com.api.rabbitadmin.exception;

/*
 * Recurso reservado del sistema (amq.*, default exchange) que la API no permite modificar -> HTTP 403.
 */
public class ProtectedResourceException extends RuntimeException {

    public ProtectedResourceException(String message) {
        super(message);
    }

    public ProtectedResourceException(String message, Throwable cause) {
        super(message, cause);
    }
}
