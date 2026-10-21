package com.api.pedidos.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.api.pedidos.client.AuthUserClient;
import com.api.pedidos.client.InventoryClient;
import com.api.pedidos.dto.AuthUserResponse;
import com.api.pedidos.dto.OrderCreateRequest;
import com.api.pedidos.dto.OrderItemRequest;
import com.api.pedidos.dto.OrderResponse;
import com.api.pedidos.dto.ProductInfoResponse;
import com.api.pedidos.dto.StockAvailabilityResponse;
import com.api.pedidos.event.OrderStatusChangedEvent;
import com.api.pedidos.exception.BusinessRuleException;
import com.api.pedidos.model.Order;
import com.api.pedidos.model.OrderItem;
import com.api.pedidos.model.OrderStatus;
import com.api.pedidos.repository.OrderRepository;
import com.api.pedidos.security.UserContext;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * Pruebas unitarias de OrderService.
 * Validan reglas de pedidos sin levantar Spring ni conectarse a MySQL/inventario real.
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock
    private OrderRepository orderRepository;

    @Mock
    private InventoryClient inventoryClient;

    @Mock
    private AuthUserClient authUserClient;

    // EP3: OrderService emite eventos de dominio que la capa messaging publica en RabbitMQ.
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private OrderService orderService;

    // Guia: valida create order validates inventory reserves stock and saves confirmed order.
    @Test
    void createOrderValidatesInventoryReservesStockAndSavesConfirmedOrder() {
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderCreateRequest request = new OrderCreateRequest(
                "Santiago Centro 123",
                List.of(new OrderItemRequest("sku-001", 2)),
                null);

        when(inventoryClient.findProductBySku("SKU-001")).thenReturn(product(productId));
        when(inventoryClient.checkAvailability(productId, 2)).thenReturn(availability(productId, true));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, client(customerId), null);

        assertThat(response.customerId()).isEqualTo(customerId);
        assertThat(response.status()).isEqualTo(OrderStatus.CONFIRMED);
        assertThat(response.totalAmount()).isEqualByComparingTo("19980.00");
        verify(inventoryClient).reserveStock(productId, 2);
    }

    // Guia: valida create order rejects duplicated sku before calling inventory.
    @Test
    void createOrderRejectsDuplicatedSkuBeforeCallingInventory() {
        OrderCreateRequest request = new OrderCreateRequest(
                "Santiago Centro 123",
                List.of(
                        new OrderItemRequest("sku-001", 1),
                        new OrderItemRequest(" SKU-001 ", 1)),
                null);

        assertThatThrownBy(() -> orderService.createOrder(request, client(UUID.randomUUID()), null))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("duplicado");

        verify(inventoryClient, never()).findProductBySku(any());
        verify(orderRepository, never()).save(any());
    }

    // Guia: valida admin can create order for cliente user.
    @Test
    void adminCanCreateOrderForClienteUser() {
        UUID adminId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        OrderCreateRequest request = new OrderCreateRequest(
                "Santiago Centro 123",
                List.of(new OrderItemRequest("sku-001", 2)),
                customerId);

        when(authUserClient.findUserById(customerId, "Bearer token")).thenReturn(cliente(customerId));
        when(inventoryClient.findProductBySku("SKU-001")).thenReturn(product(productId));
        when(inventoryClient.checkAvailability(productId, 2)).thenReturn(availability(productId, true));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.createOrder(request, admin(adminId), "Bearer token");

        assertThat(response.customerId()).isEqualTo(customerId);
        verify(authUserClient).findUserById(customerId, "Bearer token");
    }

    // Guia: valida non admin cannot create order for another customer.
    @Test
    void nonAdminCannotCreateOrderForAnotherCustomer() {
        UUID customerId = UUID.randomUUID();
        OrderCreateRequest request = new OrderCreateRequest(
                "Santiago Centro 123",
                List.of(new OrderItemRequest("sku-001", 2)),
                customerId);

        assertThatThrownBy(() -> orderService.createOrder(request, client(UUID.randomUUID()), null))
                .isInstanceOf(com.api.pedidos.exception.ForbiddenException.class)
                .hasMessageContaining("Solo ADMIN");

        verify(authUserClient, never()).findUserById(any(), any());
        verify(inventoryClient, never()).findProductBySku(any());
        verify(orderRepository, never()).save(any());
    }

    // Guia: valida admin cannot assign order to non cliente user.
    @Test
    void adminCannotAssignOrderToNonClienteUser() {
        UUID customerId = UUID.randomUUID();
        OrderCreateRequest request = new OrderCreateRequest(
                "Santiago Centro 123",
                List.of(new OrderItemRequest("sku-001", 2)),
                customerId);

        when(authUserClient.findUserById(customerId, "Bearer token"))
                .thenReturn(new AuthUserResponse(
                        customerId,
                        "operator@smartlogix.com",
                        "Operador",
                        "Pedidos",
                        true,
                        List.of("OPERADOR_PEDIDOS"),
                        OffsetDateTime.now(),
                        OffsetDateTime.now()));

        assertThatThrownBy(() -> orderService.createOrder(request, admin(UUID.randomUUID()), "Bearer token"))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("rol CLIENTE");

        verify(inventoryClient, never()).findProductBySku(any());
        verify(orderRepository, never()).save(any());
    }

    // Guia: valida cancel confirmed order releases reserved stock.
    @Test
    void cancelConfirmedOrderReleasesReservedStock() {
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Order order = confirmedOrder(customerId, productId);

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        OrderResponse response = orderService.cancel(client(customerId), orderId);

        assertThat(response.status()).isEqualTo(OrderStatus.CANCELLED);
        verify(inventoryClient).releaseReservedStock(productId, 3);
    }

    // Guia: valida ship confirmed order confirms reserved stock for operators.
    @Test
    void shipConfirmedOrderConfirmsReservedStockForOperators() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Order order = confirmedOrder(UUID.randomUUID(), productId);

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.updateStatus(operator(), orderId, new com.api.pedidos.dto.OrderStatusUpdateRequest("shipped"));

        ArgumentCaptor<Order> orderCaptor = ArgumentCaptor.forClass(Order.class);
        verify(orderRepository).save(orderCaptor.capture());
        assertThat(orderCaptor.getValue().getStatus()).isEqualTo(OrderStatus.SHIPPED);
        verify(inventoryClient).confirmReservedStock(productId, 3);
    }

    // Guia: valida shipping operator can search operational orders without customer restriction.
    @Test
    void shippingOperatorCanSearchOperationalOrdersWithoutCustomerRestriction() {
        when(orderRepository.search(isNull(), eq(OrderStatus.SHIPPED), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of()));

        orderService.search(shippingOperator(), null, "SHIPPED", 0, 10);

        verify(orderRepository).search(isNull(), eq(OrderStatus.SHIPPED), any(Pageable.class));
    }

    // Guia: valida shipping operator cannot update order status.
    @Test
    void shippingOperatorCannotUpdateOrderStatus() {
        assertThatThrownBy(() -> orderService.updateStatus(
                shippingOperator(),
                UUID.randomUUID(),
                new com.api.pedidos.dto.OrderStatusUpdateRequest("delivered")))
                .isInstanceOf(com.api.pedidos.exception.ForbiddenException.class)
                .hasMessageContaining("Solo ADMIN u OPERADOR_PEDIDOS");

        verify(orderRepository, never()).findByIdForUpdate(any());
    }

    // EP3: un envio entregado lleva el pedido CONFIRMED a SHIPPED y luego a DELIVERED, emitiendo eventos.
    @Test
    void deliveredShipmentMovesConfirmedOrderToDeliveredAndPublishesEvents() {
        UUID orderId = UUID.randomUUID();
        UUID productId = UUID.randomUUID();
        Order order = confirmedOrder(UUID.randomUUID(), productId);

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));
        when(orderRepository.save(any(Order.class))).thenAnswer(invocation -> invocation.getArgument(0));

        orderService.applyShipmentStatus(orderId, "delivered");

        assertThat(order.getStatus()).isEqualTo(OrderStatus.DELIVERED);
        verify(inventoryClient).confirmReservedStock(productId, 3);
        verify(eventPublisher, org.mockito.Mockito.times(2)).publishEvent(any(OrderStatusChangedEvent.class));
    }

    // EP3: mensaje reentregado (pedido ya en el estado destino) no produce efectos duplicados.
    @Test
    void repeatedShipmentStatusIsIdempotent() {
        UUID orderId = UUID.randomUUID();
        Order order = confirmedOrder(UUID.randomUUID(), UUID.randomUUID());
        order.setStatus(OrderStatus.SHIPPED);

        when(orderRepository.findByIdForUpdate(orderId)).thenReturn(Optional.of(order));

        orderService.applyShipmentStatus(orderId, "in_transit");

        verify(orderRepository, never()).save(any());
        verify(eventPublisher, never()).publishEvent(any(Object.class));
    }

    private UserContext client(UUID customerId) {
        return new UserContext(customerId, Set.of("CLIENTE"));
    }

    private UserContext operator() {
        return new UserContext(UUID.randomUUID(), Set.of("OPERADOR_PEDIDOS"));
    }

    private UserContext shippingOperator() {
        return new UserContext(UUID.randomUUID(), Set.of("OPERADOR_ENVIOS"));
    }

    private UserContext admin(UUID adminId) {
        return new UserContext(adminId, Set.of("ADMIN"));
    }

    private AuthUserResponse cliente(UUID customerId) {
        return new AuthUserResponse(
                customerId,
                "cliente@smartlogix.com",
                "Cliente",
                "Demo",
                true,
                List.of("CLIENTE"),
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private ProductInfoResponse product(UUID productId) {
        return new ProductInfoResponse(
                productId,
                "SKU-001",
                "Producto test",
                "Producto de prueba",
                new BigDecimal("9990.00"),
                "general",
                true,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private StockAvailabilityResponse availability(UUID productId, boolean available) {
        return new StockAvailabilityResponse(
                productId,
                "SKU-001",
                "Producto test",
                2,
                10,
                0,
                10,
                "Santiago",
                true,
                available);
    }

    private Order confirmedOrder(UUID customerId, UUID productId) {
        Order order = new Order();
        order.setCustomerId(customerId);
        order.setStatus(OrderStatus.CONFIRMED);
        order.setShippingAddress("Santiago Centro 123");

        OrderItem item = new OrderItem();
        item.setProductId(productId);
        item.setSku("SKU-001");
        item.setProductName("Producto test");
        item.setQuantity(3);
        item.setUnitPrice(new BigDecimal("9990.00"));
        order.addItem(item);
        order.calculateTotalAmount();
        return order;
    }
}
