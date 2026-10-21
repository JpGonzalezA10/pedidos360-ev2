package com.api.pedidos.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.api.pedidos.exception.BusinessRuleException;
import com.api.pedidos.exception.ExternalServiceUnavailableException;
import com.api.pedidos.exception.InsufficientStockException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/*
 * Pruebas de configuracion del cliente de inventario.
 * Aseguran que reglas de negocio no abran el Circuit Breaker.
 */
class InventoryClientConfigTest {

    private final InventoryClientConfig config = new InventoryClientConfig();

    // Guia: valida creates inventory rest client.
    @Test
    void createsInventoryRestClient() {
        assertThat(config.inventoryRestClient("http://localhost:8082")).isNotNull();
    }

    // Guia: valida inventory circuit breaker records only availability failures.
    @Test
    void inventoryCircuitBreakerRecordsOnlyAvailabilityFailures() {
        CircuitBreaker circuitBreaker = config.inventoryCircuitBreaker();

        assertThat(circuitBreaker.getName()).isEqualTo("inventory-service");
        assertThat(circuitBreaker.getCircuitBreakerConfig().getRecordExceptionPredicate())
                .accepts(new ExternalServiceUnavailableException("inventario caido"))
                .accepts(new ResourceAccessException("sin conexion"))
                .accepts(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "error",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8))
                .rejects(new BusinessRuleException("regla"))
                .rejects(new InsufficientStockException("sin stock"));
    }
}
