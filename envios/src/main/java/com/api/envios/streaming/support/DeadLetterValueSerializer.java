package com.api.envios.streaming.support;

import java.nio.charset.StandardCharsets;
import org.apache.kafka.common.serialization.Serializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

/*
 * EP5 - Serializer de la Dead Letter Topic.
 * Un registro fallido puede llegar al recoverer de tres formas y todas deben quedar legibles en la DLT:
 *  - byte[]: fallo de deserializacion -> se reenvia el contenido original sin tocarlo.
 *  - String / null: claves o tombstones -> UTF-8 o null.
 *  - DTO: fallo de validacion o de negocio -> se serializa a JSON.
 */
public class DeadLetterValueSerializer implements Serializer<Object> {

    private final JacksonJsonSerializer<Object> json = new JacksonJsonSerializer<>();

    @Override
    public byte[] serialize(String topic, Object data) {
        if (data == null) {
            return null;
        }
        if (data instanceof byte[] raw) {
            return raw;
        }
        if (data instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8);
        }
        return json.serialize(topic, data);
    }

    @Override
    public void close() {
        json.close();
    }
}
