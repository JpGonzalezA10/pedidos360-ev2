package com.api.pedidos.messaging.support;

/*
 * Error permanente al procesar un mensaje (payload invalido, datos faltantes, regla de negocio).
 * Reintentar no ayuda: el mensaje se rechaza sin requeue y termina en la DLQ.
 */
public class NonRecoverableMessageException extends RuntimeException {

    public NonRecoverableMessageException(String message) {
        super(message);
    }

    public NonRecoverableMessageException(String message, Throwable cause) {
        super(message, cause);
    }
}
