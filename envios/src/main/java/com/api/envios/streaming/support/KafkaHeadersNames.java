package com.api.envios.streaming.support;

/*
 * EP5 - Nombres de headers Kafka y claves MDC compartidos por productores y consumidores.
 * El correlation-id nace en el productor (eventId) y viaja en el header para trazar el evento
 * de punta a punta en los logs de todos los microservicios.
 */
public final class KafkaHeadersNames {

    public static final String CORRELATION_ID = "X-Correlation-Id";
    public static final String EVENT_TYPE = "X-Event-Type";
    public static final String SOURCE_SERVICE = "X-Source-Service";

    public static final String MDC_CORRELATION_ID = "correlationId";
    public static final String MDC_RECORD = "kafkaRecord";

    private KafkaHeadersNames() {
    }
}
