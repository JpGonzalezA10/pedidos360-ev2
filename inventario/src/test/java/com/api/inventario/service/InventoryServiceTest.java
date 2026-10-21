package com.api.inventario.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.api.inventario.dto.InventoryResponse;
import com.api.inventario.dto.InventoryUpdateRequest;
import com.api.inventario.dto.StockAvailabilityResponse;
import com.api.inventario.dto.StockResponse;
import com.api.inventario.exception.BusinessRuleException;
import com.api.inventario.exception.ResourceNotFoundException;
import com.api.inventario.model.Inventory;
import com.api.inventario.model.Product;
import com.api.inventario.repository.InventoryRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * Pruebas unitarias de InventoryService.
 * Validan reglas de stock y relacion producto-inventario sin conectarse a MySQL.
 */
@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    private InventoryRepository inventoryRepository;

    @Mock
    private ProductService productService;

    @InjectMocks
    private InventoryService inventoryService;

    // Guia: valida update by product id stores absolute values.
    @Test
    void updateByProductIdStoresAbsoluteValues() {
        // Verifica que el update guarde los valores recibidos como cantidades absolutas.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        InventoryUpdateRequest request = new InventoryUpdateRequest(20, 5, "Santiago", 3);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryResponse response = inventoryService.updateByProductId(productId, request);

        assertThat(response.stockAvailable()).isEqualTo(20);
        assertThat(response.stockReserved()).isEqualTo(5);
        assertThat(response.warehouseLocation()).isEqualTo("Santiago");
        assertThat(response.reorderPoint()).isEqualTo(3);
    }

    // Guia: valida update by product id creates inventory when missing.
    @Test
    void updateByProductIdCreatesInventoryWhenMissing() {
        // Si el producto existe pero no tiene inventario, el service crea la fila.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        InventoryUpdateRequest request = new InventoryUpdateRequest(12, 2, "Valparaiso", 4);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.empty());
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        InventoryResponse response = inventoryService.updateByProductId(productId, request);

        assertThat(response.productId()).isEqualTo(productId);
        assertThat(response.stockAvailable()).isEqualTo(12);
        assertThat(response.stockReserved()).isEqualTo(2);
        assertThat(response.warehouseLocation()).isEqualTo("Valparaiso");
    }

    // Guia: valida update by product id rejects reserved greater than available.
    @Test
    void updateByProductIdRejectsReservedGreaterThanAvailable() {
        // Regla principal de consistencia: no se puede reservar mas de lo disponible.
        UUID productId = UUID.randomUUID();
        InventoryUpdateRequest request = new InventoryUpdateRequest(10, 11, "Santiago", 3);

        assertThatThrownBy(() -> inventoryService.updateByProductId(productId, request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("stock reservado no puede superar");
    }

    // Guia: valida find stock by product id calculates free stock and reorder status.
    @Test
    void findStockByProductIdCalculatesFreeStockAndReorderStatus() {
        // Verifica calculo de stock libre y alerta de reposicion.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        Inventory inventory = inventory(product, 8, 3, 8);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        StockResponse response = inventoryService.findStockByProductId(productId);

        assertThat(response.stockFree()).isEqualTo(5);
        assertThat(response.belowReorderPoint()).isTrue();
    }

    // Guia: valida find stock by product id rejects missing inventory.
    @Test
    void findStockByProductIdRejectsMissingInventory() {
        // Diferencia producto existente de inventario aun no creado.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inventoryService.findStockByProductId(productId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Inventario no encontrado para producto " + productId);
    }

    // Guia: valida check availability returns true when free stock covers quantity.
    @Test
    void checkAvailabilityReturnsTrueWhenFreeStockCoversQuantity() {
        // Permite validar disponibilidad sin modificar inventario.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        Inventory inventory = inventory(product, 20, 5, 3);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductId(productId)).thenReturn(Optional.of(inventory));

        StockAvailabilityResponse response = inventoryService.checkAvailability(productId, 12);

        assertThat(response.requestedQuantity()).isEqualTo(12);
        assertThat(response.stockFree()).isEqualTo(15);
        assertThat(response.productActive()).isTrue();
        assertThat(response.available()).isTrue();
    }

    // Guia: valida reserve stock increases reserved when free stock is enough.
    @Test
    void reserveStockIncreasesReservedWhenFreeStockIsEnough() {
        // Reserva unidades sin alterar el stock total disponible.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        Inventory inventory = inventory(product, 20, 5, 3);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductIdForUpdate(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockResponse response = inventoryService.reserveStock(productId, 4);

        assertThat(response.stockAvailable()).isEqualTo(20);
        assertThat(response.stockReserved()).isEqualTo(9);
        assertThat(response.stockFree()).isEqualTo(11);
    }

    // Guia: valida reserve stock rejects insufficient free stock.
    @Test
    void reserveStockRejectsInsufficientFreeStock() {
        // Evita sobre-reservar cuando el stock libre no alcanza.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        Inventory inventory = inventory(product, 10, 8, 3);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductIdForUpdate(productId)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> inventoryService.reserveStock(productId, 3))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Stock insuficiente");
    }

    // Guia: valida reserve stock rejects inactive product.
    @Test
    void reserveStockRejectsInactiveProduct() {
        // Un producto con baja logica no debe poder reservarse para nuevos pedidos.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        product.setActive(false);
        Inventory inventory = inventory(product, 20, 5, 3);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductIdForUpdate(productId)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> inventoryService.reserveStock(productId, 1))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("producto inactivo");
    }

    // Guia: valida release reserved stock decreases only reserved stock.
    @Test
    void releaseReservedStockDecreasesOnlyReservedStock() {
        // La liberacion revierte reservas sin sumar stock total.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        Inventory inventory = inventory(product, 20, 8, 3);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductIdForUpdate(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockResponse response = inventoryService.releaseReservedStock(productId, 3);

        assertThat(response.stockAvailable()).isEqualTo(20);
        assertThat(response.stockReserved()).isEqualTo(5);
        assertThat(response.stockFree()).isEqualTo(15);
    }

    // Guia: valida release reserved stock rejects quantity greater than reserved.
    @Test
    void releaseReservedStockRejectsQuantityGreaterThanReserved() {
        // No se puede liberar una cantidad que nunca estuvo reservada.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        Inventory inventory = inventory(product, 20, 2, 3);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductIdForUpdate(productId)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> inventoryService.releaseReservedStock(productId, 3))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mas stock del que esta reservado");
    }

    // Guia: valida confirm reserved stock decreases available and reserved stock.
    @Test
    void confirmReservedStockDecreasesAvailableAndReservedStock() {
        // Confirmar una venta descuenta tanto el total como la reserva.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        Inventory inventory = inventory(product, 20, 8, 3);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductIdForUpdate(productId)).thenReturn(Optional.of(inventory));
        when(inventoryRepository.save(any(Inventory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StockResponse response = inventoryService.confirmReservedStock(productId, 4);

        assertThat(response.stockAvailable()).isEqualTo(16);
        assertThat(response.stockReserved()).isEqualTo(4);
        assertThat(response.stockFree()).isEqualTo(12);
    }

    // Guia: valida confirm reserved stock rejects quantity greater than reserved.
    @Test
    void confirmReservedStockRejectsQuantityGreaterThanReserved() {
        // Obliga a que el pedido haya reservado unidades antes de confirmar venta.
        UUID productId = UUID.randomUUID();
        Product product = product(productId);
        Inventory inventory = inventory(product, 20, 2, 3);

        when(productService.findEntityById(productId)).thenReturn(product);
        when(inventoryRepository.findByProductIdForUpdate(productId)).thenReturn(Optional.of(inventory));

        assertThatThrownBy(() -> inventoryService.confirmReservedStock(productId, 3))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("mas stock del que esta reservado");
    }

    // Guia: valida stock operations reject invalid quantity.
    @Test
    void stockOperationsRejectInvalidQuantity() {
        // Valida tambien a nivel service para llamadas internas, no solo HTTP.
        UUID productId = UUID.randomUUID();

        assertThatThrownBy(() -> inventoryService.reserveStock(productId, 0))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("cantidad debe ser mayor que cero");
    }

    private Inventory inventory(Product product, int stockAvailable, int stockReserved, int reorderPoint) {
        // Helper para construir una entidad de inventario reutilizable en pruebas.
        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setStockAvailable(stockAvailable);
        inventory.setStockReserved(stockReserved);
        inventory.setWarehouseLocation("Santiago");
        inventory.setReorderPoint(reorderPoint);
        return inventory;
    }

    private Product product(UUID productId) {
        // Helper minimo de producto; solo incluye campos usados por DTOs de inventario.
        Product product = new Product();
        product.setId(productId);
        product.setSku("SLX-001");
        product.setName("Producto test");
        return product;
    }
}
