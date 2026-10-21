package com.api.envios.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.api.envios.dto.PageResponse;
import com.api.envios.dto.ShipmentCreateRequest;
import com.api.envios.dto.ShipmentEventResponse;
import com.api.envios.dto.ShipmentResponse;
import com.api.envios.dto.ShipmentStatusUpdateRequest;
import com.api.envios.dto.ShipmentUpdateRequest;
import com.api.envios.service.ShipmentService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;

/*
 * Pruebas unitarias del controller de envios.
 * Verifican delegacion al service y respuestas HTTP sin iniciar Spring MVC.
 */
@ExtendWith(MockitoExtension.class)
class ShipmentControllerTest {

    @Mock
    private ShipmentService shipmentService;

    // Guia: valida create returns created location.
    @Test
    void createReturnsCreatedLocation() {
        ShipmentController controller = new ShipmentController(shipmentService);
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ShipmentCreateRequest request = new ShipmentCreateRequest(orderId, null, "Chilexpress", "TRK-001");
        ShipmentResponse shipment = shipment(shipmentId, orderId);

        when(shipmentService.create(request)).thenReturn(shipment);

        ResponseEntity<ShipmentResponse> response = controller.create(request);

        assertThat(response.getStatusCode().value()).isEqualTo(201);
        assertThat(response.getHeaders().getFirst(HttpHeaders.LOCATION)).isEqualTo("/api/shipments/" + shipmentId);
        assertThat(response.getBody()).isSameAs(shipment);
    }

    // Guia: valida endpoints delegate to shipment service.
    @Test
    void endpointsDelegateToShipmentService() {
        ShipmentController controller = new ShipmentController(shipmentService);
        UUID shipmentId = UUID.randomUUID();
        UUID orderId = UUID.randomUUID();
        ShipmentResponse shipment = shipment(shipmentId, orderId);
        PageResponse<ShipmentResponse> page = new PageResponse<>(List.of(shipment), 0, 20, 1, 1, true, true);
        ShipmentUpdateRequest updateRequest = new ShipmentUpdateRequest("Nueva direccion", "Blue", "TRK-002");
        ShipmentStatusUpdateRequest statusRequest = new ShipmentStatusUpdateRequest(
                "in_transit",
                "CD Santiago",
                "Retirado",
                OffsetDateTime.now());
        ShipmentEventResponse event = new ShipmentEventResponse(
                UUID.randomUUID(),
                shipmentId,
                "pending",
                null,
                "Envio creado",
                OffsetDateTime.now(),
                OffsetDateTime.now());

        when(shipmentService.search(eq(orderId), eq("pending"), eq("Blue"), eq("TRK-001"), eq(0), eq(20)))
                .thenReturn(page);
        when(shipmentService.findById(shipmentId)).thenReturn(shipment);
        when(shipmentService.findByOrderId(orderId)).thenReturn(shipment);
        when(shipmentService.findByTrackingNumber("TRK-001")).thenReturn(shipment);
        when(shipmentService.findEvents(shipmentId)).thenReturn(List.of(event));
        when(shipmentService.update(shipmentId, updateRequest)).thenReturn(shipment);
        when(shipmentService.updateStatus(shipmentId, statusRequest)).thenReturn(shipment);
        when(shipmentService.cancel(shipmentId)).thenReturn(shipment);

        assertThat(controller.findAll(orderId, "pending", "Blue", "TRK-001", 0, 20)).isSameAs(page);
        assertThat(controller.findById(shipmentId)).isSameAs(shipment);
        assertThat(controller.findByOrderId(orderId)).isSameAs(shipment);
        assertThat(controller.findByTrackingNumber("TRK-001")).isSameAs(shipment);
        assertThat(controller.findEvents(shipmentId)).containsExactly(event);
        assertThat(controller.update(shipmentId, updateRequest)).isSameAs(shipment);
        assertThat(controller.updateStatus(shipmentId, statusRequest)).isSameAs(shipment);
        assertThat(controller.cancel(shipmentId)).isSameAs(shipment);
    }

    private ShipmentResponse shipment(UUID shipmentId, UUID orderId) {
        return new ShipmentResponse(
                shipmentId,
                orderId,
                "pending",
                "Av. Providencia 1234",
                "Chilexpress",
                "TRK-001",
                null,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }
}
