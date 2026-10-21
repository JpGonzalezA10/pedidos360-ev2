package com.api.pedidos.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.api.pedidos.exception.BusinessRuleException;
import com.api.pedidos.exception.ExternalServiceUnavailableException;
import com.api.pedidos.exception.ResourceNotFoundException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

/*
 * Pruebas de configuracion del cliente de auth-service.
 * Validan que el Circuit Breaker registre solo fallas de disponibilidad.
 */
class AuthClientConfigTest {

    private final AuthClientConfig config = new AuthClientConfig();

    // Guia: valida creates auth rest client.
    @Test
    void createsAuthRestClient() {
        assertThat(config.authRestClient("http://localhost:8081")).isNotNull();
    }

    // Guia: valida auth circuit breaker records only availability failures.
    @Test
    void authCircuitBreakerRecordsOnlyAvailabilityFailures() {
        CircuitBreaker circuitBreaker = config.authCircuitBreaker();

        assertThat(circuitBreaker.getName()).isEqualTo("auth-service");
        assertThat(circuitBreaker.getCircuitBreakerConfig().getRecordExceptionPredicate())
                .accepts(new ExternalServiceUnavailableException("auth caido"))
                .accepts(new ResourceAccessException("sin conexion"))
                .accepts(HttpServerErrorException.create(
                        HttpStatus.INTERNAL_SERVER_ERROR,
                        "error",
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8))
                .rejects(new BusinessRuleException("regla"))
                .rejects(new ResourceNotFoundException("usuario no existe"));
    }
}
