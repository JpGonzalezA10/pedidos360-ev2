package com.api.inventario.service;

import com.api.inventario.exception.ResourceNotFoundException;
import com.api.inventario.model.Product;
import com.api.inventario.repository.ProductRepository;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * EP5 - Analitica de demanda por SKU construida desde el stream de pedidos (sin dependencias de Kafka).
 *
 * - Pedido confirmado: suma unidades pedidas por SKU (valida el SKU contra el catalogo en BD).
 * - Pedido cancelado: resta las unidades de ese pedido.
 * Errores:
 *  - SKU inexistente -> ResourceNotFoundException (permanente: el consumidor lo envia a la DLT).
 *  - BD caida -> DataAccessException (transitorio: el DefaultErrorHandler reintenta con backoff).
 */
@Service
@Transactional(readOnly = true)
public class DemandAnalyticsService {

    private static final Logger log = LoggerFactory.getLogger(DemandAnalyticsService.class);

    private final ProductRepository productRepository;
    private final Map<String, DemandStats> demandBySku = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, Integer>> unitsByOrder = new ConcurrentHashMap<>();

    public DemandAnalyticsService(ProductRepository productRepository) {
        this.productRepository = productRepository;
    }

    /** Lineas confirmadas de un pedido: sku -> unidades. */
    public void registerConfirmedOrder(UUID orderId, Map<String, Integer> unitsBySku, OffsetDateTime occurredAt) {
        // Primero valida todo el pedido: si un SKU no existe no se aplica ninguna linea.
        Map<String, String> names = new ConcurrentHashMap<>();
        unitsBySku.keySet().forEach(sku -> names.put(sku, productRepository.findBySku(sku)
                .map(Product::getName)
                .orElseThrow(() -> new ResourceNotFoundException("SKU " + sku + " del pedido " + orderId
                        + " no existe en el catalogo"))));
        unitsByOrder.put(orderId, Map.copyOf(unitsBySku));
        unitsBySku.forEach((sku, units) -> demandBySku.merge(sku,
                new DemandStats(sku, names.get(sku), units, 1, 0, occurredAt),
                (current, added) -> current.add(added.unitsOrdered(), occurredAt)));
        log.info("[DEMAND] pedido {} confirmado: {}", orderId, unitsBySku);
    }

    public void registerCancelledOrder(UUID orderId) {
        Map<String, Integer> units = unitsByOrder.remove(orderId);
        if (units == null) {
            log.info("[DEMAND] pedido {} cancelado sin lineas registradas (confirmado antes del stream)", orderId);
            return;
        }
        units.forEach((sku, quantity) -> demandBySku.computeIfPresent(sku, (key, stats) -> stats.cancel(quantity)));
        log.info("[DEMAND] pedido {} cancelado: se descuentan {}", orderId, units);
    }

    public List<DemandStats> ranking() {
        return demandBySku.values().stream()
                .sorted(Comparator.comparingInt(DemandStats::netUnits).reversed())
                .toList();
    }

    /** Estadistica de demanda de un SKU. */
    public record DemandStats(
            String sku,
            String productName,
            int unitsOrdered,
            int confirmedOrders,
            int unitsCancelled,
            OffsetDateTime lastOrderAt) {

        public int netUnits() {
            return unitsOrdered - unitsCancelled;
        }

        DemandStats add(int units, OffsetDateTime at) {
            return new DemandStats(sku, productName, unitsOrdered + units, confirmedOrders + 1, unitsCancelled, at);
        }

        DemandStats cancel(int units) {
            return new DemandStats(sku, productName, unitsOrdered, confirmedOrders, unitsCancelled + units, lastOrderAt);
        }
    }
}
