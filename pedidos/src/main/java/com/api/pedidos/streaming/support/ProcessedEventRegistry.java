package com.api.pedidos.streaming.support;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/*
 * EP5 - Idempotencia del consumidor (guia 3.3.3, patron check-then-act).
 * Kafka entrega "at-least-once": tras un rebalanceo o un fallo de commit un evento puede llegar dos veces.
 * El registro guarda los ultimos eventId procesados (LRU acotado) y descarta los duplicados.
 * Las proyecciones de este servicio viven en memoria, por lo que el registro tambien.
 */
@Component
public class ProcessedEventRegistry {

    private static final int MAX_TRACKED_EVENTS = 10_000;

    private final Map<UUID, Boolean> processed = Collections.synchronizedMap(
            new LinkedHashMap<>(256, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<UUID, Boolean> eldest) {
                    return size() > MAX_TRACKED_EVENTS;
                }
            });

    /** true si el evento ya fue procesado con exito (duplicado). */
    public boolean alreadyProcessed(UUID eventId) {
        return processed.containsKey(eventId);
    }

    /**
     * Se marca SOLO despues de procesar con exito: si el procesamiento falla y se reintenta,
     * el reintento no debe confundirse con un duplicado.
     */
    public void markProcessed(UUID eventId) {
        processed.put(eventId, Boolean.TRUE);
    }

    public int size() {
        return processed.size();
    }
}
