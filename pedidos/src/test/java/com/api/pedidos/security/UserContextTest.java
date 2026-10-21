package com.api.pedidos.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.api.pedidos.exception.UnauthorizedException;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/*
 * Pruebas unitarias del contexto recibido desde API Gateway.
 * Validan normalizacion de roles y reglas de propietario usadas por pedidos.
 */
class UserContextTest {

    // Guia: valida from headers normalizes roles and allows operational read.
    @Test
    void fromHeadersNormalizesRolesAndAllowsOperationalRead() {
        UUID userId = UUID.randomUUID();

        UserContext context = UserContext.fromHeaders(
                userId.toString(),
                " cliente, operador_envios, ADMIN ");

        assertThat(context.userId()).isEqualTo(userId);
        assertThat(context.roles()).containsExactlyInAnyOrder("CLIENTE", "OPERADOR_ENVIOS", "ADMIN");
        assertThat(context.canReadOperationalOrders()).isTrue();
        assertThat(context.canManageOrders()).isTrue();
        assertThat(context.isAdmin()).isTrue();
        assertThat(context.owns(userId)).isTrue();
    }

    // Guia: valida from headers rejects missing user id.
    @Test
    void fromHeadersRejectsMissingUserId() {
        assertThatThrownBy(() -> UserContext.fromHeaders(" ", "CLIENTE"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("identidad");
    }

    // Guia: valida from headers rejects invalid user id.
    @Test
    void fromHeadersRejectsInvalidUserId() {
        assertThatThrownBy(() -> UserContext.fromHeaders("not-a-uuid", "CLIENTE"))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("invalida");
    }

    // Guia: valida client role cannot manage operational orders.
    @Test
    void clientRoleCannotManageOperationalOrders() {
        UserContext context = new UserContext(UUID.randomUUID(), Set.of("CLIENTE"));

        assertThat(context.canManageOrders()).isFalse();
        assertThat(context.canReadOperationalOrders()).isFalse();
        assertThat(context.isAdmin()).isFalse();
    }
}
