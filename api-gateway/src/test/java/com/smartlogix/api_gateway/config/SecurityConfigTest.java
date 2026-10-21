package com.smartlogix.api_gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;

/*
 * Verifica el mapeo de roles del JWT al formato ROLE_* usado por Spring Security.
 */
class SecurityConfigTest {

    private final SecurityConfig securityConfig = new SecurityConfig();

    // Guia: valida jwt authentication converter maps smart logix roles to authorities.
    @Test
    void jwtAuthenticationConverterMapsSmartLogixRolesToAuthorities() {
        JwtAuthenticationConverter converter = securityConfig.jwtAuthenticationConverter();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .issuer("smartlogix-auth")
                .subject("admin@smartlogix.com")
                .issuedAt(Instant.parse("2026-06-06T18:00:00Z"))
                .expiresAt(Instant.parse("2026-06-06T19:00:00Z"))
                .claim("roles", List.of("ADMIN", "OPERADOR_PEDIDOS"))
                .build();

        List<String> authorities = converter.convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .sorted()
                .toList();

        assertThat(authorities).containsExactly("ROLE_ADMIN", "ROLE_OPERADOR_PEDIDOS");
    }

    // Guia: valida jwt authentication converter handles tokens without roles.
    @Test
    void jwtAuthenticationConverterHandlesTokensWithoutRoles() {
        JwtAuthenticationConverter converter = securityConfig.jwtAuthenticationConverter();
        Jwt jwt = Jwt.withTokenValue("token")
                .header("alg", "HS256")
                .issuer("smartlogix-auth")
                .subject("cliente@smartlogix.com")
                .issuedAt(Instant.parse("2026-06-06T18:00:00Z"))
                .expiresAt(Instant.parse("2026-06-06T19:00:00Z"))
                .build();

        List<String> roleAuthorities = converter.convert(jwt).getAuthorities().stream()
                .map(GrantedAuthority::getAuthority)
                .filter(authority -> authority.startsWith("ROLE_"))
                .toList();

        assertThat(roleAuthorities).isEmpty();
    }
}
