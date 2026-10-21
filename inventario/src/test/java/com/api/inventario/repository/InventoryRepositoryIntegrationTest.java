package com.api.inventario.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.api.inventario.model.Inventory;
import com.api.inventario.model.Product;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

/*
 * Pruebas de integracion JPA con H2 embebido.
 * Validan mapeos, repositorios y callbacks de entidades sin depender de MySQL local.
 */
@DataJpaTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:inventario-it;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.type.preferred_uuid_jdbc_type=CHAR",
        "spring.flyway.enabled=false"
})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class InventoryRepositoryIntegrationTest {

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private InventoryRepository inventoryRepository;

    // Guia: valida persists product and inventory relation.
    @Test
    void persistsProductAndInventoryRelation() {
        // Valida la relacion uno a uno y fechas generadas por callbacks JPA.
        Product product = product("SLX-IT-001", true);
        Product savedProduct = productRepository.saveAndFlush(product);

        Inventory inventory = new Inventory();
        inventory.setProduct(savedProduct);
        inventory.setStockAvailable(20);
        inventory.setStockReserved(5);
        inventory.setWarehouseLocation("Santiago");
        inventory.setReorderPoint(3);

        Inventory savedInventory = inventoryRepository.saveAndFlush(inventory);

        Optional<Inventory> found = inventoryRepository.findByProductId(savedProduct.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getId()).isEqualTo(savedInventory.getId());
        assertThat(found.get().getProduct().getSku()).isEqualTo("SLX-IT-001");
        assertThat(found.get().getCreatedAt()).isNotNull();
        assertThat(found.get().getUpdatedAt()).isNotNull();
    }

    // Guia: valida find by active true excludes inactive products.
    @Test
    void findByActiveTrueExcludesInactiveProducts() {
        // Valida la consulta usada por GET /api/products.
        productRepository.save(product("SLX-ACTIVE", true));
        productRepository.save(product("SLX-INACTIVE", false));
        productRepository.flush();

        List<Product> activeProducts = productRepository.findByActiveTrue();

        assertThat(activeProducts)
                .extracting(Product::getSku)
                .contains("SLX-ACTIVE")
                .doesNotContain("SLX-INACTIVE");
    }

    // Guia: valida find by product id for update returns inventory with product.
    @Test
    void findByProductIdForUpdateReturnsInventoryWithProduct() {
        // Valida la consulta con bloqueo usada por operaciones transaccionales de stock.
        Product savedProduct = productRepository.saveAndFlush(product("SLX-LOCK", true));

        Inventory inventory = new Inventory();
        inventory.setProduct(savedProduct);
        inventory.setStockAvailable(10);
        inventory.setStockReserved(2);
        inventory.setReorderPoint(1);
        Inventory savedInventory = inventoryRepository.saveAndFlush(inventory);

        Optional<Inventory> lockedInventory = inventoryRepository.findByProductIdForUpdate(savedProduct.getId());

        assertThat(lockedInventory).isPresent();
        assertThat(lockedInventory.get().getId()).isEqualTo(savedInventory.getId());
        assertThat(lockedInventory.get().getProduct().getName()).isEqualTo("Producto " + savedProduct.getSku());
    }

    // Guia: valida searches products and inventory with filters and pagination.
    @Test
    void searchesProductsAndInventoryWithFiltersAndPagination() {
        // Valida queries paginadas usadas por los listados publicos.
        Product firstProduct = productRepository.save(product("SLX-PAGE-001", true));
        Product secondProduct = productRepository.save(product("SLX-PAGE-002", true));
        productRepository.flush();

        Inventory firstInventory = inventory(firstProduct, 5, 1, "Santiago", 10);
        Inventory secondInventory = inventory(secondProduct, 25, 5, "Valparaiso", 3);
        inventoryRepository.save(firstInventory);
        inventoryRepository.saveAndFlush(secondInventory);

        Page<Product> products = productRepository.searchActive(
                "PAGE",
                "Producto",
                "Integracion",
                PageRequest.of(0, 10, Sort.by("sku").ascending()));
        Page<Inventory> lowStockInventory = inventoryRepository.search(
                "PAGE",
                "Santiago",
                true,
                PageRequest.of(0, 10, Sort.by("product.sku").ascending()));

        assertThat(products.getTotalElements()).isEqualTo(2);
        assertThat(lowStockInventory.getTotalElements()).isEqualTo(1);
        assertThat(lowStockInventory.getContent().get(0).getProduct().getSku()).isEqualTo("SLX-PAGE-001");
    }

    private Product product(String sku, boolean active) {
        Product product = new Product();
        product.setSku(sku);
        product.setName("Producto " + sku);
        product.setDescription("Producto de prueba");
        product.setUnitPrice(BigDecimal.valueOf(12990));
        product.setCategory("Integracion");
        product.setActive(active);
        return product;
    }

    private Inventory inventory(
            Product product,
            int stockAvailable,
            int stockReserved,
            String warehouseLocation,
            int reorderPoint) {
        Inventory inventory = new Inventory();
        inventory.setProduct(product);
        inventory.setStockAvailable(stockAvailable);
        inventory.setStockReserved(stockReserved);
        inventory.setWarehouseLocation(warehouseLocation);
        inventory.setReorderPoint(reorderPoint);
        return inventory;
    }
}
