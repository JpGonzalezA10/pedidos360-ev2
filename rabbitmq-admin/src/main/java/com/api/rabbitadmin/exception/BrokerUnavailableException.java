package com.api.rabbitadmin.exception;

/*
 * RabbitMQ o su Management API no responden -> HTTP 503.
 */
public class BrokerUnavailableException extends RuntimeException {

    public BrokerUnavailableException(String message) {
        super(message);
    }

    public BrokerUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
