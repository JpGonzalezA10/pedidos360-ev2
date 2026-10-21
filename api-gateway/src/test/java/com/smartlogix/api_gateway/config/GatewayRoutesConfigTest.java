package com.smartlogix.api_gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/*
 * Prueba de construccion de rutas externas del Gateway.
 * Los filtros reales se validan en runtime, pero estos tests cubren los beans de ruteo.
 */
class GatewayRoutesConfigTest {

    // Guia: valida inventory routes can be built.
    @Test
    void inventoryRoutesCanBeBuilt() {
        InventoryRoutesConfig config = new InventoryRoutesConfig();

        assertThat(config.inventoryProductsRoute("http://localhost:8081", "inventory-key")).isNotNull();
        assertThat(config.inventoryStockRoute("http://localhost:8081", "inventory-key")).isNotNull();
    }

    // Guia: valida orders route can be built.
    @Test
    void ordersRouteCanBeBuilt() {
        OrdersRoutesConfig config = new OrdersRoutesConfig();

        assertThat(config.ordersRoute("http://localhost:8084", "orders-key")).isNotNull();
    }

    // Guia: valida shipping route can be built.
    @Test
    void shippingRouteCanBeBuilt() {
        ShippingRoutesConfig config = new ShippingRoutesConfig();

        assertThat(config.shippingRoute("http://localhost:8083", "shipping-key")).isNotNull();
    }

    // EP3: valida rabbit admin route can be built.
    @Test
    void rabbitAdminRouteCanBeBuilt() {
        RabbitAdminRoutesConfig config = new RabbitAdminRoutesConfig();

        assertThat(config.rabbitAdminRoute("http://localhost:8085", "rabbit-admin-key")).isNotNull();
    }
}
