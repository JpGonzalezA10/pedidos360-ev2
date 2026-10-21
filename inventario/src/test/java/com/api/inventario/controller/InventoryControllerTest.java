package com.api.inventario.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.api.inventario.dto.InventoryResponse;
import com.api.inventario.dto.InventoryUpdateRequest;
import com.api.inventario.dto.PageResponse;
import com.api.inventario.dto.StockAvailabilityResponse;
import com.api.inventario.dto.StockOperationRequest;
import com.api.inventario.dto.StockResponse;
import com.api.inventario.service.InventoryService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/*
 * Pruebas web del InventoryController.
 * Usan MockMvc para verificar rutas, codigos HTTP y JSON sin depender de MySQL.
 */
@WebMvcTest(InventoryController.class)
class InventoryControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private InventoryService inventoryService;

    // Guia: valida find all returns inventory rows.
    @Test
    void findAllReturnsInventoryRows() throws Exception {
        // Verifica que GET /api/inventory responda una pagina con stock por producto.
        UUID productId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        InventoryResponse response = inventoryResponse(inventoryId, productId);
        when(inventoryService.search(null, null, null, 0, 20))
                .thenReturn(pageResponse(List.of(response)));

        mockMvc.perform(get("/api/inventory"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(inventoryId.toString()))
                .andExpect(jsonPath("$.content[0].productId").value(productId.toString()))
                .andExpect(jsonPath("$.content[0].stockAvailable").value(20))
                .andExpect(jsonPath("$.page").value(0));
    }

    // Guia: valida find all passes filters and pagination.
    @Test
    void findAllPassesFiltersAndPagination() throws Exception {
        // Verifica filtros de SKU, ubicacion, bajo stock y paginacion.
        InventoryResponse response = inventoryResponse(UUID.randomUUID(), UUID.randomUUID());
        when(inventoryService.search("SLX", "Santiago", true, 1, 5))
                .thenReturn(pageResponse(List.of(response)));

        mockMvc.perform(get("/api/inventory")
                        .param("sku", "SLX")
                        .param("warehouseLocation", "Santiago")
                        .param("lowStock", "true")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sku").value("SLX-001"));
    }

    // Guia: valida find inventory by product id returns inventory.
    @Test
    void findInventoryByProductIdReturnsInventory() throws Exception {
        // Verifica consulta detallada de inventario usando el id del producto.
        UUID productId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        InventoryResponse response = inventoryResponse(inventoryId, productId);
        when(inventoryService.findByProductId(productId)).thenReturn(response);

        mockMvc.perform(get("/api/inventory/{productId}", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(inventoryId.toString()))
                .andExpect(jsonPath("$.productId").value(productId.toString()));
    }

    // Guia: valida get stock returns availability.
    @Test
    void getStockReturnsAvailability() throws Exception {
        // Verifica la vista resumida de disponibilidad: stock libre y reposicion.
        UUID productId = UUID.randomUUID();
        StockResponse response = new StockResponse(
                productId,
                "SLX-001",
                "Producto test",
                20,
                5,
                15,
                "Santiago",
                false);
        when(inventoryService.findStockByProductId(productId)).thenReturn(response);

        mockMvc.perform(get("/api/inventory/{productId}/stock", productId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockFree").value(15));
    }

    // Guia: valida check availability returns requested quantity and availability.
    @Test
    void checkAvailabilityReturnsRequestedQuantityAndAvailability() throws Exception {
        // Verifica consulta de disponibilidad por cantidad para pedidos.
        UUID productId = UUID.randomUUID();
        StockAvailabilityResponse response = new StockAvailabilityResponse(
                productId,
                "SLX-001",
                "Producto test",
                10,
                20,
                5,
                15,
                "Santiago",
                true,
                true);
        when(inventoryService.checkAvailability(productId, 10)).thenReturn(response);

        mockMvc.perform(get("/api/inventory/{productId}/availability", productId)
                        .param("quantity", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestedQuantity").value(10))
                .andExpect(jsonPath("$.stockFree").value(15))
                .andExpect(jsonPath("$.available").value(true));
    }

    // Guia: valida check availability rejects missing quantity.
    @Test
    void checkAvailabilityRejectsMissingQuantity() throws Exception {
        // Verifica contrato JSON cuando falta un query param requerido.
        UUID productId = UUID.randomUUID();

        mockMvc.perform(get("/api/inventory/{productId}/availability", productId))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Falta un parametro requerido"))
                .andExpect(jsonPath("$.details.quantity").exists());
    }

    // Guia: valida update inventory returns updated values.
    @Test
    void updateInventoryReturnsUpdatedValues() throws Exception {
        // Verifica que el update use valores absolutos de stock, no incrementos.
        UUID productId = UUID.randomUUID();
        UUID inventoryId = UUID.randomUUID();
        InventoryUpdateRequest request = new InventoryUpdateRequest(20, 5, "Santiago", 3);
        InventoryResponse response = inventoryResponse(inventoryId, productId);
        when(inventoryService.updateByProductId(eq(productId), any(InventoryUpdateRequest.class)))
                .thenReturn(response);

        mockMvc.perform(put("/api/inventory/{productId}", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockAvailable").value(20))
                .andExpect(jsonPath("$.stockReserved").value(5));
    }

    // Guia: valida update inventory rejects invalid payload.
    @Test
    void updateInventoryRejectsInvalidPayload() throws Exception {
        // Verifica validaciones de DTO para evitar stock negativo en la API.
        UUID productId = UUID.randomUUID();
        String payload = """
                {
                  "stockAvailable": -1,
                  "stockReserved": 0,
                  "warehouseLocation": "Santiago",
                  "reorderPoint": 0
                }
                """;

        mockMvc.perform(put("/api/inventory/{productId}", productId)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La solicitud contiene datos invalidos"));
    }

    // Guia: valida reserve stock returns updated stock.
    @Test
    void reserveStockReturnsUpdatedStock() throws Exception {
        // Verifica endpoint que aumenta stock reservado.
        UUID productId = UUID.randomUUID();
        StockOperationRequest request = new StockOperationRequest(4);
        StockResponse response = stockResponse(productId, 20, 9);
        when(inventoryService.reserveStock(productId, 4)).thenReturn(response);

        mockMvc.perform(post("/api/inventory/{productId}/reserve", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockAvailable").value(20))
                .andExpect(jsonPath("$.stockReserved").value(9))
                .andExpect(jsonPath("$.stockFree").value(11));
    }

    // Guia: valida release reserved stock returns updated stock.
    @Test
    void releaseReservedStockReturnsUpdatedStock() throws Exception {
        // Verifica endpoint que libera reservas.
        UUID productId = UUID.randomUUID();
        StockOperationRequest request = new StockOperationRequest(3);
        StockResponse response = stockResponse(productId, 20, 5);
        when(inventoryService.releaseReservedStock(productId, 3)).thenReturn(response);

        mockMvc.perform(post("/api/inventory/{productId}/release", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockReserved").value(5))
                .andExpect(jsonPath("$.stockFree").value(15));
    }

    // Guia: valida confirm reserved stock returns updated stock.
    @Test
    void confirmReservedStockReturnsUpdatedStock() throws Exception {
        // Verifica endpoint que confirma venta y descuenta inventario total.
        UUID productId = UUID.randomUUID();
        StockOperationRequest request = new StockOperationRequest(4);
        StockResponse response = stockResponse(productId, 16, 4);
        when(inventoryService.confirmReservedStock(productId, 4)).thenReturn(response);

        mockMvc.perform(post("/api/inventory/{productId}/confirm", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stockAvailable").value(16))
                .andExpect(jsonPath("$.stockReserved").value(4))
                .andExpect(jsonPath("$.stockFree").value(12));
    }

    // Guia: valida reserve stock rejects invalid quantity.
    @Test
    void reserveStockRejectsInvalidQuantity() throws Exception {
        // Valida el body comun de operaciones de stock.
        UUID productId = UUID.randomUUID();
        String payload = """
                {
                  "quantity": 0
                }
                """;

        mockMvc.perform(post("/api/inventory/{productId}/reserve", productId)
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La solicitud contiene datos invalidos"))
                .andExpect(jsonPath("$.details.quantity").exists());
    }

    private InventoryResponse inventoryResponse(UUID inventoryId, UUID productId) {
        // Helper para crear respuestas consistentes en las pruebas del controller.
        return new InventoryResponse(
                inventoryId,
                productId,
                "SLX-001",
                "Producto test",
                20,
                5,
                "Santiago",
                3,
                null,
                null);
    }

    private StockResponse stockResponse(UUID productId, int stockAvailable, int stockReserved) {
        // Helper para construir respuestas de operaciones de stock.
        return new StockResponse(
                productId,
                "SLX-001",
                "Producto test",
                stockAvailable,
                stockReserved,
                stockAvailable - stockReserved,
                "Santiago",
                false);
    }

    private PageResponse<InventoryResponse> pageResponse(List<InventoryResponse> inventoryRows) {
        // Helper para simular respuestas paginadas del service.
        return new PageResponse<>(
                inventoryRows,
                0,
                20,
                inventoryRows.size(),
                1,
                true,
                true);
    }
}
