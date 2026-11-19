package com.api.inventario.service;

import com.api.inventario.dto.InventoryResponse;
import com.api.inventario.dto.InventoryUpdateRequest;
import com.api.inventario.dto.PageResponse;
import com.api.inventario.dto.StockAvailabilityResponse;
import com.api.inventario.dto.StockResponse;
import com.api.inventario.event.StockChangedEvent;
import com.api.inventario.exception.BusinessRuleException;
import com.api.inventario.exception.ResourceNotFoundException;
import com.api.inventario.model.Inventory;
import com.api.inventario.model.Product;
import com.api.inventario.repository.InventoryRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class InventoryService {

    /*
     * Service de inventario.
     * Orquesta la relacion producto-inventario y aplica reglas de stock antes
     * de guardar cambios en la base de datos.
     */
    private final InventoryRepository inventoryRepository;
    private final ProductService productService;
    // EP5: eventos de dominio de stock; la capa streaming los publica en Kafka tras el commit.
    private final ApplicationEventPublisher eventPublisher;

    public InventoryService(
            InventoryRepository inventoryRepository,
            ProductService productService,
            ApplicationEventPublisher eventPublisher) {
        this.inventoryRepository = inventoryRepository;
        this.productService = productService;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public List<InventoryResponse> findAll() {
        // Convierte entidades JPA a DTOs para responder sin exponer el modelo interno.
        return inventoryRepository.findAll().stream()
                .map(InventoryResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public PageResponse<InventoryResponse> search(
            String sku,
            String warehouseLocation,
            Boolean lowStock,
            int page,
            int size) {
        // Listado paginado para operacion: busqueda por SKU, bodega y bajo stock.
        PageRequest pageRequest = PageRequest.of(
                validatePage(page),
                validateSize(size),
                Sort.by("product.sku").ascending());
        return PageResponse.from(inventoryRepository.search(
                        trimToNull(sku),
                        trimToNull(warehouseLocation),
                        lowStock,
                        pageRequest)
                .map(InventoryResponse::from));
    }

    @Transactional(readOnly = true)
    public InventoryResponse findByProductId(UUID productId) {
        // La API consulta inventario por producto, no por id interno de inventario.
        return InventoryResponse.from(findEntityByProductId(productId));
    }

    @Transactional(readOnly = true)
    public StockResponse findStockByProductId(UUID productId) {
        // Vista enfocada en disponibilidad: stock total, reservado y libre.
        return StockResponse.from(findEntityByProductId(productId));
    }

    @Transactional(readOnly = true)
    public StockAvailabilityResponse checkAvailability(UUID productId, Integer quantity) {
        // Verifica si una venta podria reservar la cantidad solicitada.
        int requestedQuantity = validateQuantity(quantity);
        Inventory inventory = findEntityByProductId(productId);
        return StockAvailabilityResponse.from(inventory, requestedQuantity);
    }

    public InventoryResponse updateByProductId(UUID productId, InventoryUpdateRequest request) {
        // Antes de consultar o guardar, se validan reglas de negocio de stock.
        validateStock(request.stockAvailable(), request.stockReserved(), request.reorderPoint());

        // Valida que el producto exista; no debe existir inventario sin producto.
        Product product = productService.findEntityById(productId);
        Inventory inventory = inventoryRepository.findByProductId(productId)
                .orElseGet(() -> {
                    // Si no hay inventario previo, se crea la fila ligada al producto.
                    Inventory created = new Inventory();
                    created.setProduct(product);
                    return created;
                });

        inventory.setStockAvailable(request.stockAvailable());
        inventory.setStockReserved(request.stockReserved());
        inventory.setWarehouseLocation(trimToNull(request.warehouseLocation()));
        inventory.setReorderPoint(request.reorderPoint());

        Inventory saved = inventoryRepository.save(inventory);
        publishStockChanged(saved, StockChangedEvent.Reason.ADJUSTED);
        return InventoryResponse.from(saved);
    }

    public StockResponse reserveStock(UUID productId, Integer quantity) {
        // Reserva unidades para un pedido pendiente sin descontar el stock total.
        int requestedQuantity = validateQuantity(quantity);
        Inventory inventory = findEntityByProductIdForUpdate(productId);
        validateProductIsActive(inventory);

        int stockFree = calculateStockFree(inventory);
        if (requestedQuantity > stockFree) {
            throw new BusinessRuleException(
                    "Stock insuficiente para reservar " + requestedQuantity + " unidades");
        }

        inventory.setStockReserved(inventory.getStockReserved() + requestedQuantity);
        return saveAndPublish(inventory, StockChangedEvent.Reason.RESERVED);
    }

    public StockResponse releaseReservedStock(UUID productId, Integer quantity) {
        // Libera unidades reservadas cuando un pedido se cancela o expira.
        int requestedQuantity = validateQuantity(quantity);
        Inventory inventory = findEntityByProductIdForUpdate(productId);

        if (requestedQuantity > inventory.getStockReserved()) {
            throw new BusinessRuleException(
                    "No se puede liberar mas stock del que esta reservado");
        }

        inventory.setStockReserved(inventory.getStockReserved() - requestedQuantity);
        return saveAndPublish(inventory, StockChangedEvent.Reason.RELEASED);
    }

    public StockResponse confirmReservedStock(UUID productId, Integer quantity) {
        // Confirma una venta: descuenta del stock total y de las unidades reservadas.
        int requestedQuantity = validateQuantity(quantity);
        Inventory inventory = findEntityByProductIdForUpdate(productId);

        if (requestedQuantity > inventory.getStockReserved()) {
            throw new BusinessRuleException(
                    "No se puede confirmar mas stock del que esta reservado");
        }

        inventory.setStockReserved(inventory.getStockReserved() - requestedQuantity);
        inventory.setStockAvailable(inventory.getStockAvailable() - requestedQuantity);
        return saveAndPublish(inventory, StockChangedEvent.Reason.SOLD);
    }

    /**
     * EP5: publica la foto actual del stock de todos los productos (motivo SNAPSHOT).
     * Sirve para poblar el topico compactado despues de una carga masiva por SQL (productos semilla)
     * o para que un consumidor nuevo reconstruya su proyeccion. Devuelve la cantidad publicada.
     */
    @Transactional(readOnly = true)
    public int republishStockSnapshots() {
        List<Inventory> inventories = inventoryRepository.findAll();
        inventories.forEach(inventory -> publishStockChanged(inventory, StockChangedEvent.Reason.SNAPSHOT));
        return inventories.size();
    }

    private StockResponse saveAndPublish(Inventory inventory, StockChangedEvent.Reason reason) {
        Inventory saved = inventoryRepository.save(inventory);
        publishStockChanged(saved, reason);
        return StockResponse.from(saved);
    }

    private void publishStockChanged(Inventory inventory, StockChangedEvent.Reason reason) {
        // EP5: se publica en Kafka solo si la transaccion confirma (TransactionalEventListener).
        eventPublisher.publishEvent(StockChangedEvent.of(inventory, reason));
    }

    private Inventory findEntityByProductId(UUID productId) {
        // Distingue entre producto inexistente e inventario inexistente.
        productService.findEntityById(productId);
        return inventoryRepository.findByProductId(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inventario no encontrado para producto " + productId));
    }

    private Inventory findEntityByProductIdForUpdate(UUID productId) {
        // Primero valida producto para mantener mensajes 404 diferenciados.
        productService.findEntityById(productId);
        return inventoryRepository.findByProductIdForUpdate(productId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Inventario no encontrado para producto " + productId));
    }

    private void validateStock(int stockAvailable, int stockReserved, int reorderPoint) {
        // Los valores negativos no tienen sentido para inventario operativo.
        if (stockAvailable < 0) {
            throw new BusinessRuleException("El stock disponible no puede ser negativo");
        }
        if (stockReserved < 0) {
            throw new BusinessRuleException("El stock reservado no puede ser negativo");
        }
        if (reorderPoint < 0) {
            throw new BusinessRuleException("El punto de reposicion no puede ser negativo");
        }
        // El stock reservado representa unidades comprometidas dentro del disponible.
        if (stockReserved > stockAvailable) {
            throw new BusinessRuleException("El stock reservado no puede superar el stock disponible");
        }
    }

    private int validateQuantity(Integer quantity) {
        if (quantity == null || quantity < 1) {
            throw new BusinessRuleException("La cantidad debe ser mayor que cero");
        }
        return quantity;
    }

    private int validatePage(int page) {
        if (page < 0) {
            throw new BusinessRuleException("La pagina no puede ser negativa");
        }
        return page;
    }

    private int validateSize(int size) {
        if (size < 1 || size > 100) {
            throw new BusinessRuleException("El tamano de pagina debe estar entre 1 y 100");
        }
        return size;
    }

    private int calculateStockFree(Inventory inventory) {
        return inventory.getStockAvailable() - inventory.getStockReserved();
    }

    private void validateProductIsActive(Inventory inventory) {
        if (!inventory.getProduct().isActive()) {
            throw new BusinessRuleException("No se puede reservar stock de un producto inactivo");
        }
    }

    private String trimToNull(String value) {
        // Normaliza campos opcionales para no persistir cadenas vacias.
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }
}
