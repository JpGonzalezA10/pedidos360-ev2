package com.api.pedidos.service;

import com.api.pedidos.client.AuthUserClient;
import com.api.pedidos.client.InventoryClient;
import com.api.pedidos.dto.AuthUserResponse;
import com.api.pedidos.dto.OrderCreateRequest;
import com.api.pedidos.dto.OrderItemRequest;
import com.api.pedidos.dto.OrderResponse;
import com.api.pedidos.dto.OrderStatusUpdateRequest;
import com.api.pedidos.dto.PageResponse;
import com.api.pedidos.dto.ProductInfoResponse;
import com.api.pedidos.dto.StockAvailabilityResponse;
import com.api.pedidos.event.OrderConfirmedEvent;
import com.api.pedidos.event.OrderStatusChangedEvent;
import com.api.pedidos.exception.BusinessRuleException;
import com.api.pedidos.exception.ForbiddenException;
import com.api.pedidos.exception.InsufficientStockException;
import com.api.pedidos.exception.ResourceNotFoundException;
import com.api.pedidos.model.Order;
import com.api.pedidos.model.OrderItem;
import com.api.pedidos.model.OrderStatus;
import com.api.pedidos.repository.OrderRepository;
import com.api.pedidos.security.UserContext;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

@Service
@Transactional
public class OrderService {

    /*
     * Service de pedidos.
     * Centraliza reglas de negocio: propietario, estados, validacion y reserva de stock.
     */
    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED_TRANSITIONS = Map.of(
            OrderStatus.PENDING, Set.of(OrderStatus.CONFIRMED, OrderStatus.CANCELLED, OrderStatus.PAYMENT_FAILED),
            OrderStatus.CONFIRMED, Set.of(OrderStatus.SHIPPED, OrderStatus.CANCELLED),
            OrderStatus.SHIPPED, Set.of(OrderStatus.DELIVERED),
            OrderStatus.DELIVERED, Set.of(),
            OrderStatus.CANCELLED, Set.of(),
            OrderStatus.PAYMENT_FAILED, Set.of(OrderStatus.CANCELLED));

    // Estados de envio (microservicio envios) que impactan el estado del pedido.
    private static final String SHIPMENT_IN_TRANSIT = "in_transit";
    private static final String SHIPMENT_DELIVERED = "delivered";
    private static final String SOURCE_API = "API";
    private static final String SOURCE_SHIPPING = "ENVIOS";

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orderRepository;
    private final InventoryClient inventoryClient;
    private final AuthUserClient authUserClient;
    // EP3: eventos de dominio; la capa messaging los publica en RabbitMQ tras el commit.
    private final ApplicationEventPublisher eventPublisher;

    public OrderService(
            OrderRepository orderRepository,
            InventoryClient inventoryClient,
            AuthUserClient authUserClient,
            ApplicationEventPublisher eventPublisher) {
        this.orderRepository = orderRepository;
        this.inventoryClient = inventoryClient;
        this.authUserClient = authUserClient;
        this.eventPublisher = eventPublisher;
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> search(
            UserContext userContext,
            UUID requestedCustomerId,
            String requestedStatus,
            int page,
            int size) {
        // CLIENTE solo consulta sus pedidos; roles operativos pueden consultar pedidos para su modulo.
        UUID effectiveCustomerId = userContext.canReadOperationalOrders()
                ? requestedCustomerId
                : userContext.userId();
        OrderStatus status = normalizeStatusOrNull(requestedStatus);
        PageRequest pageRequest = PageRequest.of(
                validatePage(page),
                validateSize(size),
                Sort.by("createdAt").descending());
        return PageResponse.from(orderRepository.search(effectiveCustomerId, status, pageRequest)
                .map(OrderResponse::from));
    }

    @Transactional(readOnly = true)
    public OrderResponse findById(UserContext userContext, UUID id) {
        Order order = findEntityById(id);
        validateCanRead(userContext, order);
        return OrderResponse.from(order);
    }

    @Transactional(readOnly = true)
    public PageResponse<OrderResponse> findByCustomerId(
            UserContext userContext,
            UUID customerId,
            int page,
            int size) {
        if (!userContext.canReadOperationalOrders() && !userContext.owns(customerId)) {
            throw new ForbiddenException("No puedes consultar pedidos de otro cliente");
        }

        PageRequest pageRequest = PageRequest.of(
                validatePage(page),
                validateSize(size),
                Sort.by("createdAt").descending());
        return PageResponse.from(orderRepository.findByCustomerId(customerId, pageRequest)
                .map(OrderResponse::from));
    }

    public OrderResponse createOrder(
            OrderCreateRequest request,
            UserContext userContext,
            String authorizationHeader) {
        validateUniqueSkus(request.items());
        UUID customerId = resolveCustomerId(request.customerId(), userContext, authorizationHeader);

        Order order = new Order();
        order.setCustomerId(customerId);
        order.setShippingAddress(request.shippingAddress().trim());
        order.setStatus(OrderStatus.PENDING);

        List<ReservedStock> reservedStocks = new ArrayList<>();
        resolveItems(request).forEach(item -> order.addItem(item.toOrderItem()));
        order.calculateTotalAmount();

        try {
            // La reserva real se realiza antes de confirmar el pedido.
            for (OrderItem item : order.getItems()) {
                inventoryClient.reserveStock(item.getProductId(), item.getQuantity());
                reservedStocks.add(new ReservedStock(item.getProductId(), item.getQuantity()));
            }
            order.setStatus(OrderStatus.CONFIRMED);
            Order saved = orderRepository.save(order);
            eventPublisher.publishEvent(OrderConfirmedEvent.from(saved));
            return OrderResponse.from(saved);
        } catch (RuntimeException exception) {
            releaseReservedStocks(reservedStocks);
            throw exception;
        }
    }

    private UUID resolveCustomerId(
            UUID requestedCustomerId,
            UserContext userContext,
            String authorizationHeader) {
        if (requestedCustomerId == null) {
            return userContext.userId();
        }

        if (!userContext.isAdmin()) {
            if (userContext.owns(requestedCustomerId)) {
                return requestedCustomerId;
            }
            throw new ForbiddenException("Solo ADMIN puede asignar pedidos a otro cliente");
        }

        AuthUserResponse customer = authUserClient.findUserById(requestedCustomerId, authorizationHeader);
        if (!customer.enabled()) {
            throw new BusinessRuleException("El cliente asignado esta deshabilitado");
        }
        if (!AuthUserClient.hasRole(customer, "CLIENTE")) {
            throw new BusinessRuleException("El usuario asignado debe tener rol CLIENTE");
        }
        return customer.id();
    }

    public OrderResponse updateStatus(
            UserContext userContext,
            UUID id,
            OrderStatusUpdateRequest request) {
        if (!userContext.canManageOrders()) {
            throw new ForbiddenException("Solo ADMIN u OPERADOR_PEDIDOS pueden actualizar estados de pedidos");
        }

        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido no encontrado con id " + id));
        OrderStatus newStatus = normalizeStatus(request.status());
        if (order.getStatus() == newStatus) {
            return OrderResponse.from(order);
        }

        return OrderResponse.from(changeStatus(order, newStatus, SOURCE_API));
    }

    public OrderResponse cancel(UserContext userContext, UUID id) {
        Order order = orderRepository.findByIdForUpdate(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido no encontrado con id " + id));
        if (!userContext.canManageOrders() && !userContext.owns(order.getCustomerId())) {
            throw new ForbiddenException("No puedes cancelar pedidos de otro cliente");
        }
        if (order.getStatus() == OrderStatus.CANCELLED) {
            return OrderResponse.from(order);
        }

        return OrderResponse.from(changeStatus(order, OrderStatus.CANCELLED, SOURCE_API));
    }

    /**
     * EP3: sincroniza el pedido con el estado logistico informado por envios (via RabbitMQ).
     * in_transit -> SHIPPED (confirma el stock reservado), delivered -> DELIVERED.
     * Es idempotente: si el pedido ya esta en el estado destino no hace nada,
     * por lo que un mensaje reentregado no genera efectos duplicados.
     */
    public void applyShipmentStatus(UUID orderId, String shipmentStatus) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido no encontrado con id " + orderId));
        String normalized = shipmentStatus == null ? "" : shipmentStatus.trim().toLowerCase(Locale.ROOT);

        if (SHIPMENT_IN_TRANSIT.equals(normalized)) {
            advanceTo(order, OrderStatus.SHIPPED);
        } else if (SHIPMENT_DELIVERED.equals(normalized)) {
            advanceTo(order, OrderStatus.SHIPPED);
            advanceTo(order, OrderStatus.DELIVERED);
        } else {
            log.info("Estado de envio {} no modifica el pedido {}", normalized, orderId);
        }
    }

    private void advanceTo(Order order, OrderStatus target) {
        if (order.getStatus() == target
                || (target == OrderStatus.SHIPPED && order.getStatus() == OrderStatus.DELIVERED)) {
            return;
        }
        changeStatus(order, target, SOURCE_SHIPPING);
    }

    /** Aplica una transicion valida, sus efectos en inventario y emite el evento de dominio. */
    private Order changeStatus(Order order, OrderStatus newStatus, String source) {
        OrderStatus previousStatus = order.getStatus();
        validateStatusTransition(previousStatus, newStatus);
        applyInventorySideEffects(order, newStatus);
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        eventPublisher.publishEvent(OrderStatusChangedEvent.of(
                saved.getId(), saved.getCustomerId(), previousStatus, newStatus, source));
        return saved;
    }

    private List<ResolvedOrderItem> resolveItems(OrderCreateRequest request) {
        List<ResolvedOrderItem> resolvedItems = new ArrayList<>();
        for (OrderItemRequest itemRequest : request.items()) {
            String sku = normalizeSku(itemRequest.sku());
            int quantity = itemRequest.quantity();

            ProductInfoResponse product = inventoryClient.findProductBySku(sku);
            if (!product.active()) {
                throw new BusinessRuleException("El producto con SKU " + sku + " no esta activo");
            }

            StockAvailabilityResponse availability = inventoryClient.checkAvailability(product.id(), quantity);
            if (!availability.productActive() || !availability.available()) {
                throw new InsufficientStockException("Stock insuficiente para el producto con SKU " + sku);
            }

            resolvedItems.add(new ResolvedOrderItem(product, quantity));
        }
        return resolvedItems;
    }

    private void applyInventorySideEffects(Order order, OrderStatus newStatus) {
        if (order.getStatus() == OrderStatus.CONFIRMED && newStatus == OrderStatus.CANCELLED) {
            for (OrderItem item : order.getItems()) {
                inventoryClient.releaseReservedStock(item.getProductId(), item.getQuantity());
            }
        }
        if (order.getStatus() == OrderStatus.CONFIRMED && newStatus == OrderStatus.SHIPPED) {
            for (OrderItem item : order.getItems()) {
                inventoryClient.confirmReservedStock(item.getProductId(), item.getQuantity());
            }
        }
    }

    private void releaseReservedStocks(List<ReservedStock> reservedStocks) {
        // Compensacion simple para evitar stock reservado si falla guardar la orden.
        for (ReservedStock reservedStock : reservedStocks) {
            try {
                inventoryClient.releaseReservedStock(reservedStock.productId(), reservedStock.quantity());
            } catch (RuntimeException ignored) {
                // Se conserva el error original; esta liberacion es de mejor esfuerzo.
            }
        }
    }

    private void validateCanRead(UserContext userContext, Order order) {
        if (!userContext.canReadOperationalOrders() && !userContext.owns(order.getCustomerId())) {
            throw new ForbiddenException("No puedes consultar pedidos de otro cliente");
        }
    }

    private Order findEntityById(UUID id) {
        return orderRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pedido no encontrado con id " + id));
    }

    private void validateStatusTransition(OrderStatus currentStatus, OrderStatus newStatus) {
        Set<OrderStatus> allowedStatuses = ALLOWED_TRANSITIONS.getOrDefault(currentStatus, Set.of());
        if (!allowedStatuses.contains(newStatus)) {
            throw new BusinessRuleException(
                    "No se puede cambiar el pedido de " + currentStatus + " a " + newStatus);
        }
    }

    private void validateUniqueSkus(List<OrderItemRequest> items) {
        Set<String> uniqueSkus = new HashSet<>();
        for (OrderItemRequest item : items) {
            String sku = normalizeSku(item.sku());
            if (!uniqueSkus.add(sku)) {
                throw new BusinessRuleException("El SKU " + sku + " esta duplicado en el pedido");
            }
        }
    }

    private OrderStatus normalizeStatus(String status) {
        try {
            return OrderStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new BusinessRuleException("Estado de pedido no permitido: " + status);
        }
    }

    private OrderStatus normalizeStatusOrNull(String status) {
        if (status == null || status.trim().isEmpty()) {
            return null;
        }
        return normalizeStatus(status);
    }

    private String normalizeSku(String sku) {
        return sku.trim().toUpperCase(Locale.ROOT);
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

    private record ResolvedOrderItem(ProductInfoResponse product, int quantity) {

        private OrderItem toOrderItem() {
            OrderItem item = new OrderItem();
            item.setProductId(product.id());
            item.setSku(product.sku());
            item.setProductName(product.name());
            item.setQuantity(quantity);
            item.setUnitPrice(product.unitPrice());
            return item;
        }
    }

    private record ReservedStock(UUID productId, int quantity) {
    }
}
