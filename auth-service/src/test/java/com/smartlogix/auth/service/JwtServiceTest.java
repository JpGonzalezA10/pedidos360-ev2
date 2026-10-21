package com.smartlogix.auth.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.nimbusds.jose.jwk.source.ImmutableSecret;
import com.smartlogix.auth.dto.JwtToken;
import com.smartlogix.auth.model.Role;
import com.smartlogix.auth.model.User;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;

/*
 * Prueba el contrato real del JWT que consumen React, Gateway y microservicios.
 */
class JwtServiceTest {

    // Guia: valida create token includes required smart logix claims.
    @Test
    void createTokenIncludesRequiredSmartLogixClaims() {
        String secret = "smartlogix-auth-test-secret-12345678901234567890";
        SecretKey secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        JwtEncoder encoder = new NimbusJwtEncoder(new ImmutableSecret<>(secretKey));
        JwtDecoder decoder = NimbusJwtDecoder.withSecretKey(secretKey)
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        JwtService jwtService = new JwtService(encoder, "smartlogix-auth-test", 30);
        User user = user(Set.of(role("CLIENTE"), role("ADMIN")));

        JwtToken token = jwtService.createToken(user);
        Jwt decoded = decoder.decode(token.value());

        assertThat(decoded.getClaimAsString("iss")).isEqualTo("smartlogix-auth-test");
        assertThat(decoded.getSubject()).isEqualTo("usuario@smartlogix.com");
        assertThat(decoded.getClaimAsString("userId")).isEqualTo(user.getId().toString());
        assertThat(decoded.getClaimAsString("email")).isEqualTo("usuario@smartlogix.com");
        assertThat(decoded.getClaimAsStringList("roles")).isEqualTo(List.of("ADMIN", "CLIENTE"));
        assertThat(decoded.getClaimAsString("issuedAt")).isNotBlank();
        assertThat(decoded.getClaimAsString("expiration")).isNotBlank();
        assertThat(Duration.between(decoded.getIssuedAt(), decoded.getExpiresAt()).toMinutes()).isEqualTo(30);
    }

    private User user(Set<Role> roles) {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("usuario@smartlogix.com");
        user.setPasswordHash("hash");
        user.setEnabled(true);
        user.setRoles(roles);
        user.setCreatedAt(OffsetDateTime.now());
        user.setUpdatedAt(OffsetDateTime.now());
        return user;
    }

    private Role role(String name) {
        Role role = new Role();
        role.setId(UUID.randomUUID());
        role.setName(name);
        role.setCreatedAt(OffsetDateTime.now());
        role.setUpdatedAt(OffsetDateTime.now());
        return role;
    }
}
