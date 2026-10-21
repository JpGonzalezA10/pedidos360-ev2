package com.smartlogix.api_gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

/*
 * Pruebas unitarias de la validacion JWT del Gateway (BFF) contra Azure AD.
 * La firma e issuer los valida NimbusJwtDecoder con el JWKS del tenant (requiere red);
 * aqui se prueba sin red la regla propia: la audience debe ser la app Pedidos360-SPA.
 *
 * EP3: reemplaza la prueba anterior basada en la clave HMAC de auth-service,
 * que dejo de compilar al migrar el gateway a Azure AD.
 */
class JwtConfigTest {

    private static final String EXPECTED_AUDIENCE = "51f7e0fc-5612-4909-b8f4-4f810f5176c4";

    private final AudienceValidator validator = new AudienceValidator(EXPECTED_AUDIENCE);

    @Test
    void acceptsTokenIssuedForPedidos360Audience() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithAudience(EXPECTED_AUDIENCE));

        assertThat(result.hasErrors()).isFalse();
    }

    @Test
    void rejectsTokenIssuedForAnotherApplication() {
        OAuth2TokenValidatorResult result = validator.validate(jwtWithAudience("otra-aplicacion"));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).anyMatch(error -> "invalid_token".equals(error.getErrorCode()));
    }

    private Jwt jwtWithAudience(String audience) {
        Instant now = Instant.now();
        return Jwt.withTokenValue("token")
                .header("alg", "RS256")
                .issuer("https://login.microsoftonline.com/tenant/v2.0")
                .audience(List.of(audience))
                .claim("roles", List.of("ADMIN"))
                .issuedAt(now)
                .expiresAt(now.plusSeconds(1800))
                .build();
    }
}
