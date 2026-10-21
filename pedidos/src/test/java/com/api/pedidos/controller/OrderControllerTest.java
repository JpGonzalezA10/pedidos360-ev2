package com.api.pedidos.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.api.pedidos.dto.OrderCreateRequest;
import com.api.pedidos.dto.OrderItemRequest;
import com.api.pedidos.dto.OrderResponse;
import com.api.pedidos.dto.OrderStatusUpdateRequest;
import com.api.pedidos.dto.PageResponse;
import com.api.pedidos.model.OrderStatus;
import com.api.pedidos.security.UserContext;
import com.api.pedidos.service.OrderService;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/*
 * Pruebas unitarias del controller.
 * Verifican delegacion a OrderService y contratos HTTP sin levantar servidor.
 */
@ExtendWith(MockitoExtension.class)
class OrderControllerTest {

    @Mock
    private OrderService orderService;

    // Guia: valida create order returns created location and delegates authorization.
    @Test
    void createOrderReturnsCreatedLocationAndDelegatesAuthorization() {
        OrderController controller = new OrderController(orderService);
        UUID orderId = UUID.randomUUID();
        UUID userId = UUID.randomUUID();
        OrderCreateRequest request = new OrderCreateRequest(
                "Av. Siempre Viva 123",
                List.of(new OrderItemRequest("SKU-001", 1)),
                null);

        when(orderService.createOrder(eq(request), any(UserContext.class), eq("Bearer token")))
                .thenReturn(order(orderId, userId));

        ResponseEntity<OrderResponse> response = controller.createOrder(
                userId.toString(),
                "CLIENTE",
                "Bearer token",
                request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/api/orders/" + orderId);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(orderId);
    }

    // Guia: valida find all builds user context and passes pagination filters.
    @Test
    void findAllBuildsUserContextAndPassesPaginationFilters() {
        OrderController controller = new OrderController(orderService);
        UUID userId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        PageResponse<OrderResponse> pageResponse = new PageResponse<>(List.of(), 1, 5, 0, 0, true, true);

        when(orderService.search(any(UserContext.class), eq(customerId), eq("confirmed"), eq(1), eq(5)))
                .thenReturn(pageResponse);

        PageResponse<OrderResponse> response = controller.findAll(
                userId.toString(),
                "operador_pedidos",
                customerId,
                "confirmed",
                1,
                5);

        assertThat(response).isSameAs(pageResponse);
        ArgumentCaptor<UserContext> contextCaptor = ArgumentCaptor.forClass(UserContext.class);
        verify(orderService).search(contextCaptor.capture(), eq(customerId), eq("confirmed"), eq(1), eq(5));
        assertThat(contextCaptor.getValue().roles()).containsExactly("OPERADOR_PEDIDOS");
    }

    // Guia: valida endpoints delegate to service with parsed user context.
    @Test
    void endpointsDelegateToServiceWithParsedUserContext() {
        OrderController controller = new OrderController(orderService);
        UUID userId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        UUID customerId = UUID.randomUUID();
        OrderStatusUpdateRequest request = new OrderStatusUpdateRequest("cancelled");
        OrderResponse order = order(orderId, customerId);
        PageResponse<OrderResponse> page = new PageResponse<>(List.of(order), 0, 20, 1, 1, true, true);

        when(orderService.findById(any(UserContext.class), eq(orderId))).thenReturn(order);
        when(orderService.findByCustomerId(any(UserContext.class), eq(customerId), eq(0), eq(20))).thenReturn(page);
        when(orderService.updateStatus(any(UserContext.class), eq(orderId), eq(request))).thenReturn(order);
        when(orderService.cancel(any(UserContext.class), eq(orderId))).thenReturn(order);

        assertThat(controller.findById(userId.toString(), "CLIENTE", orderId)).isSameAs(order);
        assertThat(controller.findByCustomerId(userId.toString(), "CLIENTE", customerId, 0, 20)).isSameAs(page);
        assertThat(controller.updateStatus(userId.toString(), "ADMIN", orderId, request)).isSameAs(order);
        assertThat(controller.cancel(userId.toString(), "ADMIN", orderId)).isSameAs(order);
    }

    private OrderResponse order(UUID orderId, UUID customerId) {
        return new OrderResponse(
                orderId,
                customerId,
                OrderStatus.CONFIRMED,
                "Av. Siempre Viva 123",
                new BigDecimal("9990.00"),
                OffsetDateTime.now(),
                OffsetDateTime.now(),
                List.of());
    }
}
