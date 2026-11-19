package com.api.pedidos.streaming.support;

/*
 * EP5 - Error permanente al procesar un evento Kafka (payload valido en formato pero
 * inconsistente con el negocio). El DefaultErrorHandler no lo reintenta: va directo a la DLT.
 */
public class NonRetryableEventException extends RuntimeException {

    public NonRetryableEventException(String message) {
        super(message);
    }
}
