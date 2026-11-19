package com.api.pedidos.service;

import com.api.pedidos.exception.BusinessRuleException;
import com.api.pedidos.exception.ResourceNotFoundException;
import com.api.pedidos.model.StockSnapshot;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/*
 * EP5 - Logica de dominio de la proyeccion de stock (sin dependencias de Kafka).
 * El consumidor Kafka solo traduce el mensaje y delega aqui: la regla de negocio queda separada
 * de la infraestructura de mensajeria y se prueba sin broker.
 *
 * Reglas:
 *  - Un snapshot inconsistente (reservado > disponible) es un error permanente del productor.
 *  - Un snapshot mas antiguo que el guardado se ignora (llegada fuera de orden tras un replay).
 */
@Service
public class StockProjectionService {

    private static final Logger log = LoggerFactory.getLogger(StockProjectionService.class);

    private final Map<String, StockSnapshot> snapshotsBySku = new ConcurrentHashMap<>();

    /** Aplica el snapshot y devuelve true si actualizo la proyeccion. */
    public boolean apply(StockSnapshot snapshot) {
        if (snapshot.stockReserved() > snapshot.stockAvailable()) {
            throw new BusinessRuleException("Snapshot inconsistente para " + snapshot.sku()
                    + ": reservado " + snapshot.stockReserved() + " > disponible " + snapshot.stockAvailable());
        }
        boolean[] updated = {false};
        snapshotsBySku.compute(snapshot.sku(), (sku, current) -> {
            if (current != null && current.occurredAt().isAfter(snapshot.occurredAt())) {
                return current;
            }
            updated[0] = true;
            return snapshot;
        });
        if (updated[0]) {
            log.info("[STOCK-PROJECTION] sku={} libre={} reposicion={} motivo={}{}",
                    snapshot.sku(), snapshot.stockFree(), snapshot.reorderPoint(), snapshot.lastReason(),
                    snapshot.lowStock() ? " (STOCK BAJO)" : "");
        } else {
            log.info("[STOCK-PROJECTION] sku={} snapshot antiguo ignorado ({})", snapshot.sku(), snapshot.occurredAt());
        }
        return updated[0];
    }

    public List<StockSnapshot> findAll() {
        return snapshotsBySku.values().stream()
                .sorted(Comparator.comparing(StockSnapshot::sku))
                .toList();
    }

    public StockSnapshot findBySku(String sku) {
        StockSnapshot snapshot = snapshotsBySku.get(sku);
        if (snapshot == null) {
            throw new ResourceNotFoundException("Sin snapshot de stock para el SKU " + sku);
        }
        return snapshot;
    }
}
