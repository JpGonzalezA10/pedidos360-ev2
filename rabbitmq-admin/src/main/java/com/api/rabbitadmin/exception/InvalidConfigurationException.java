package com.api.rabbitadmin.exception;

/*
 * Configuracion coherente en formato pero invalida para el broker (ej. DLX inexistente) -> HTTP 422.
 */
public class InvalidConfigurationException extends RuntimeException {

    public InvalidConfigurationException(String message) {
        super(message);
    }

    public InvalidConfigurationException(String message, Throwable cause) {
        super(message, cause);
    }
}
