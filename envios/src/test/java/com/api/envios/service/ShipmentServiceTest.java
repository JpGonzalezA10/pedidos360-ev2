package com.api.envios.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.api.envios.client.OrdersClient;
import com.api.envios.dto.OrderInfoResponse;
import com.api.envios.dto.ShipmentCreateRequest;
import com.api.envios.dto.ShipmentResponse;
import com.api.envios.dto.ShipmentStatusUpdateRequest;
import com.api.envios.exception.BusinessRuleException;
import com.api.envios.model.Shipment;
import com.api.envios.model.ShipmentEvent;
import com.api.envios.repository.ShipmentEventRepository;
import com.api.envios.repository.ShipmentRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * Pruebas unitarias de ShipmentService.
 * Validan reglas logisticas sin levantar Spring ni conectar MySQL/pedidos real.
 */
@ExtendWith(MockitoExtension.class)
class ShipmentServiceTest {

    @Mock
    private ShipmentRepository shipmentRepository;

    @Mock
    private ShipmentEventRepository shipmentEventRepository;

    @Mock
    private OrdersClient ordersClient;

    // EP3: ShipmentService emite eventos de dominio que la capa messaging publica en RabbitMQ.
    @Mock
    private org.springframework.context.ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private ShipmentService shipmentService;

    // Guia: valida create validates confirmed order uses order address and registers initial event.
    @Test
    void createValidatesConfirmedOrderUsesOrderAddressAndRegistersInitialEvent() {
        UUID orderId = UUID.randomUUID();
        ShipmentCreateRequest request = new ShipmentCreateRequest(
                orderId,
                null,
                "Chilexpress",
                "TRK-001");

        when(ordersClient.findOrderById(orderId)).thenReturn(order(orderId, "CONFIRMED"));
        when(shipmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(shipmentRepository.existsByTrackingNumber("TRK-001")).thenReturn(false);
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(shipmentEventRepository.save(any(ShipmentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShipmentResponse response = shipmentService.create(request);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo("pending");
        assertThat(response.shippingAddress()).isEqualTo("Av. Providencia 1234, Santiago");
        assertThat(response.carrier()).isEqualTo("Chilexpress");
        assertThat(response.trackingNumber()).isEqualTo("TRK-001");

        ArgumentCaptor<ShipmentEvent> eventCaptor = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(shipmentEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("pending");
        assertThat(eventCaptor.getValue().getDescription()).isEqualTo("Envio creado");
    }

    // Guia: valida create from shipped order starts in transit and registers event.
    @Test
    void createFromShippedOrderStartsInTransitAndRegistersEvent() {
        UUID orderId = UUID.randomUUID();
        ShipmentCreateRequest request = new ShipmentCreateRequest(
                orderId,
                null,
                null,
                null);

        when(ordersClient.findOrderById(orderId)).thenReturn(order(orderId, "SHIPPED"));
        when(shipmentRepository.existsByOrderId(orderId)).thenReturn(false);
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(shipmentEventRepository.save(any(ShipmentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShipmentResponse response = shipmentService.create(request);

        assertThat(response.orderId()).isEqualTo(orderId);
        assertThat(response.status()).isEqualTo("in_transit");
        assertThat(response.shippedAt()).isNotNull();

        ArgumentCaptor<ShipmentEvent> eventCaptor = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(shipmentEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("in_transit");
        assertThat(eventCaptor.getValue().getDescription()).isEqualTo("Envio creado");
        assertThat(eventCaptor.getValue().getOccurredAt()).isEqualTo(response.shippedAt());
    }

    // Guia: valida create rejects order that is not confirmed.
    @Test
    void createRejectsOrderThatIsNotConfirmed() {
        UUID orderId = UUID.randomUUID();
        ShipmentCreateRequest request = new ShipmentCreateRequest(
                orderId,
                null,
                null,
                null);

        when(ordersClient.findOrderById(orderId)).thenReturn(order(orderId, "PENDING"));

        assertThatThrownBy(() -> shipmentService.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("pedidos confirmados");

        verify(shipmentRepository, never()).save(any());
        verify(shipmentEventRepository, never()).save(any());
    }

    // Guia: valida create rejects duplicated shipment for order.
    @Test
    void createRejectsDuplicatedShipmentForOrder() {
        UUID orderId = UUID.randomUUID();
        ShipmentCreateRequest request = new ShipmentCreateRequest(
                orderId,
                "Nueva direccion 123",
                null,
                null);

        when(ordersClient.findOrderById(orderId)).thenReturn(order(orderId, "CONFIRMED"));
        when(shipmentRepository.existsByOrderId(orderId)).thenReturn(true);

        assertThatThrownBy(() -> shipmentService.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Ya existe un envio");

        verify(shipmentRepository, never()).save(any());
    }

    // Guia: valida update status to in transit sets shipped at and registers event.
    @Test
    void updateStatusToInTransitSetsShippedAtAndRegistersEvent() {
        UUID shipmentId = UUID.randomUUID();
        OffsetDateTime occurredAt = OffsetDateTime.parse("2026-05-16T12:00:00Z");
        Shipment shipment = shipment(shipmentId, "ready_to_ship");
        ShipmentStatusUpdateRequest request = new ShipmentStatusUpdateRequest(
                "in_transit",
                "CD Santiago",
                "Despacho entregado a transportista",
                occurredAt);

        when(shipmentRepository.findByIdForUpdate(shipmentId)).thenReturn(Optional.of(shipment));
        when(shipmentRepository.save(any(Shipment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(shipmentEventRepository.save(any(ShipmentEvent.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ShipmentResponse response = shipmentService.updateStatus(shipmentId, request);

        assertThat(response.status()).isEqualTo("in_transit");
        assertThat(response.shippedAt()).isEqualTo(occurredAt);

        ArgumentCaptor<ShipmentEvent> eventCaptor = ArgumentCaptor.forClass(ShipmentEvent.class);
        verify(shipmentEventRepository).save(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getStatus()).isEqualTo("in_transit");
        assertThat(eventCaptor.getValue().getLocation()).isEqualTo("CD Santiago");
    }

    // Guia: valida update status rejects invalid transition.
    @Test
    void updateStatusRejectsInvalidTransition() {
        UUID shipmentId = UUID.randomUUID();
        Shipment shipment = shipment(shipmentId, "pending");
        ShipmentStatusUpdateRequest request = new ShipmentStatusUpdateRequest(
                "delivered",
                null,
                null,
                null);

        when(shipmentRepository.findByIdForUpdate(shipmentId)).thenReturn(Optional.of(shipment));

        assertThatThrownBy(() -> shipmentService.updateStatus(shipmentId, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("No se puede cambiar");

        verify(shipmentRepository, never()).save(any());
        verify(shipmentEventRepository, never()).save(any());
    }

    private OrderInfoResponse order(UUID orderId, String status) {
        return new OrderInfoResponse(
                orderId,
                UUID.randomUUID(),
                status,
                "Av. Providencia 1234, Santiago",
                new BigDecimal("19980.00"),
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    private Shipment shipment(UUID shipmentId, String status) {
        Shipment shipment = new Shipment();
        shipment.setId(shipmentId);
        shipment.setOrderId(UUID.randomUUID());
        shipment.setStatus(status);
        shipment.setShippingAddress("Av. Providencia 1234, Santiago");
        shipment.setCarrier("Chilexpress");
        shipment.setTrackingNumber("TRK-001");
        return shipment;
    }
}
