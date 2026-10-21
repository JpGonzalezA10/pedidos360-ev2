package com.api.rabbitadmin.exception;

/*
 * El recurso ya existe o esta en uso y la operacion no puede completarse -> HTTP 409.
 */
public class ResourceConflictException extends RuntimeException {

    public ResourceConflictException(String message) {
        super(message);
    }

    public ResourceConflictException(String message, Throwable cause) {
        super(message, cause);
    }
}
