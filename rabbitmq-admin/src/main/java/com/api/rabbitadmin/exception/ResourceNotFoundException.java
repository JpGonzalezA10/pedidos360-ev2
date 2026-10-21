package com.api.rabbitadmin.exception;

/*
 * El recurso solicitado (cola o exchange) no existe en el broker -> HTTP 404.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }

    public ResourceNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
