package com.api.envios.service;

import com.api.envios.exception.BusinessRuleException;
import com.api.envios.exception.ResourceNotFoundException;
import java.time.OffsetDateTime;
import java.util.TreeMap;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/*
 * EP5 - Seguimiento operativo de pedidos para el area de despacho, alimentado por el stream de pedidos
 * (sin dependencias de Kafka).
 *
 * Mantiene la linea de tiempo de cada pedido y el tablero de pedidos por estado, para saber que
 * pedidos confirmados aun no salen a despacho. RabbitMQ sigue siendo quien CREA el despacho (EP3);
 * Kafka aporta la vista historica y en tiempo real sin consultar a pedidos por HTTP.
 */
@Service
public class OrderTrackingService {

    private static final Logger log = LoggerFactory.getLogger(OrderTrackingService.class);
    private static final Set<String> KNOWN_STATUSES =
            Set.of("PENDING", "CONFIRMED", "SHIPPED", "DELIVERED", "CANCELLED", "PAYMENT_FAILED");

    private final Map<UUID, OrderTimeline> timelines = new ConcurrentHashMap<>();

    public OrderTimeline registerStatus(UUID orderId, String status, String source, OffsetDateTime occurredAt) {
        if (!KNOWN_STATUSES.contains(status)) {
            throw new BusinessRuleException("Estado de pedido desconocido: " + status);
        }
        OrderTimeline timeline = timelines.compute(orderId, (id, current) -> {
            OrderTimeline base = current == null ? new OrderTimeline(id, new CopyOnWriteArrayList<>()) : current;
            base.entries().add(new TimelineEntry(status, source, occurredAt));
            base.entries().sort(Comparator.comparing(TimelineEntry::occurredAt));
            return base;
        });
        log.info("[TRACKING] pedido {} -> {} (origen {}); eventos registrados={}",
                orderId, status, source, timeline.entries().size());
        return timeline;
    }

    public OrderTimeline findTimeline(UUID orderId) {
        OrderTimeline timeline = timelines.get(orderId);
        if (timeline == null) {
            throw new ResourceNotFoundException("Sin eventos de stream para el pedido " + orderId);
        }
        return timeline;
    }

    /** Tablero: cantidad de pedidos por estado actual. */
    public Map<String, Long> countByCurrentStatus() {
        Map<String, Long> counts = new TreeMap<>();
        timelines.values().forEach(timeline -> counts.merge(timeline.currentStatus(), 1L, Long::sum));
        return counts;
    }

    /** Pedidos confirmados que aun no se despachan (pendientes para el operador de envios). */
    public List<UUID> pendingDispatch() {
        return timelines.values().stream()
                .filter(timeline -> "CONFIRMED".equals(timeline.currentStatus()))
                .map(OrderTimeline::orderId)
                .toList();
    }

    public record OrderTimeline(UUID orderId, List<TimelineEntry> entries) {

        public String currentStatus() {
            return entries.isEmpty() ? "UNKNOWN" : entries.get(entries.size() - 1).status();
        }
    }

    public record TimelineEntry(String status, String source, OffsetDateTime occurredAt) {
    }
}
