package com.api.inventario.service;

import com.api.inventario.event.LowStockDetectedEvent;
import com.api.inventario.model.Inventory;
import com.api.inventario.repository.InventoryRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/*
 * EP3: evaluacion asincrona de stock bajo.
 * Se ejecuta al recibir pedido.confirmado (RabbitMQ) y no en la reserva sincrona,
 * para no agregar latencia a la creacion del pedido. Si el stock libre de un producto
 * queda en o bajo su punto de reposicion, emite LowStockDetectedEvent.
 */
@Service
@Transactional(readOnly = true)
public class StockAlertService {

    private static final Logger log = LoggerFactory.getLogger(StockAlertService.class);

    private final InventoryRepository inventoryRepository;
    private final ApplicationEventPublisher eventPublisher;

    public StockAlertService(InventoryRepository inventoryRepository, ApplicationEventPublisher eventPublisher) {
        this.inventoryRepository = inventoryRepository;
        this.eventPublisher = eventPublisher;
    }

    /** Revisa los productos del pedido y devuelve las alertas emitidas. */
    public List<LowStockDetectedEvent> evaluateProducts(Collection<UUID> productIds, UUID orderId) {
        List<LowStockDetectedEvent> alerts = new ArrayList<>();
        for (UUID productId : productIds) {
            inventoryRepository.findByProductId(productId).ifPresentOrElse(
                    inventory -> toAlertIfLow(inventory, orderId).ifPresent(alert -> {
                        alerts.add(alert);
                        eventPublisher.publishEvent(alert);
                    }),
                    () -> log.warn("Producto {} del pedido {} sin inventario registrado", productId, orderId));
        }
        return alerts;
    }

    private Optional<LowStockDetectedEvent> toAlertIfLow(Inventory inventory, UUID orderId) {
        int stockFree = inventory.getStockAvailable() - inventory.getStockReserved();
        if (stockFree > inventory.getReorderPoint()) {
            return Optional.empty();
        }
        return Optional.of(new LowStockDetectedEvent(
                UUID.randomUUID(),
                inventory.getProduct().getId(),
                inventory.getProduct().getSku(),
                inventory.getProduct().getName(),
                inventory.getStockAvailable(),
                inventory.getStockReserved(),
                stockFree,
                inventory.getReorderPoint(),
                orderId,
                OffsetDateTime.now()));
    }
}
