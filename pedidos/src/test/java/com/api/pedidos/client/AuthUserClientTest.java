package com.api.pedidos.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.api.pedidos.dto.AuthUserResponse;
import com.api.pedidos.exception.UnauthorizedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

/*
 * Pruebas de reglas locales del cliente de auth-service.
 * No invocan red; cubren validaciones previas y normalizacion de roles.
 */
class AuthUserClientTest {

    // Guia: valida find user by id rejects missing authorization header before calling auth.
    @Test
    void findUserByIdRejectsMissingAuthorizationHeaderBeforeCallingAuth() {
        AuthUserClient client = new AuthUserClient(
                RestClient.builder().baseUrl("http://localhost").build(),
                CircuitBreaker.ofDefaults("auth"));

        assertThatThrownBy(() -> client.findUserById(UUID.randomUUID(), " "))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("token");
    }

    // Guia: valida has role is case insensitive and handles null roles.
    @Test
    void hasRoleIsCaseInsensitiveAndHandlesNullRoles() {
        AuthUserResponse user = new AuthUserResponse(
                UUID.randomUUID(),
                "cliente@smartlogix.com",
                "Cliente",
                "Demo",
                true,
                List.of("cliente", "OPERADOR_PEDIDOS"),
                OffsetDateTime.now(),
                OffsetDateTime.now());

        assertThat(AuthUserClient.hasRole(user, "CLIENTE")).isTrue();
        assertThat(AuthUserClient.hasRole(user, "admin")).isFalse();
        assertThat(AuthUserClient.hasRole(userWithNullRoles(), "CLIENTE")).isFalse();
    }

    private AuthUserResponse userWithNullRoles() {
        return new AuthUserResponse(
                UUID.randomUUID(),
                "sinroles@smartlogix.com",
                "Sin",
                "Roles",
                true,
                null,
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }
}
