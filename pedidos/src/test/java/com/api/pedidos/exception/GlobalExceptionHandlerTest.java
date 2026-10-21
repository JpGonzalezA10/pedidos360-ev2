package com.api.pedidos.exception;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpInputMessage;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingServletRequestParameterException;

/*
 * Pruebas del contrato JSON de errores de pedidos.
 * Evitan duplicar manejo de excepciones en controllers.
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/orders");

    // Guia: valida maps known domain exceptions to expected statuses.
    @Test
    void mapsKnownDomainExceptionsToExpectedStatuses() {
        assertResponse(handler.handleNotFound(new ResourceNotFoundException("no existe"), request),
                HttpStatus.NOT_FOUND, "no existe");
        assertResponse(handler.handleBusinessRule(new BusinessRuleException("regla invalida"), request),
                HttpStatus.BAD_REQUEST, "regla invalida");
        assertResponse(handler.handleInsufficientStock(new InsufficientStockException("sin stock"), request),
                HttpStatus.CONFLICT, "sin stock");
        assertResponse(handler.handleExternalServiceUnavailable(
                        new ExternalServiceUnavailableException("dependencia caida"), request),
                HttpStatus.SERVICE_UNAVAILABLE, "dependencia caida");
        assertResponse(handler.handleUnauthorized(new UnauthorizedException("sin identidad"), request),
                HttpStatus.UNAUTHORIZED, "sin identidad");
        assertResponse(handler.handleForbidden(new ForbiddenException("sin permiso"), request),
                HttpStatus.FORBIDDEN, "sin permiso");
    }

    // Guia: valida maps request and infrastructure exceptions to stable responses.
    @Test
    void mapsRequestAndInfrastructureExceptionsToStableResponses() {
        assertResponse(handler.handleMissingRequestParameter(
                        new MissingServletRequestParameterException("status", "String"), request),
                HttpStatus.BAD_REQUEST, "Falta un parametro requerido");
        assertResponse(handler.handleMessageNotReadable(
                        new HttpMessageNotReadableException("mal json", Mockito.mock(HttpInputMessage.class)), request),
                HttpStatus.BAD_REQUEST, "JSON de solicitud invalido o mal formado");
        assertResponse(handler.handleDataIntegrityViolation(
                        new DataIntegrityViolationException("duplicado"), request),
                HttpStatus.CONFLICT, "La solicitud viola restricciones de integridad de datos");
        assertResponse(handler.handleMediaTypeNotSupported(
                        new HttpMediaTypeNotSupportedException("xml"), request),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Tipo de contenido no soportado");
        assertResponse(handler.handleUnexpected(new RuntimeException("boom"), request),
                HttpStatus.INTERNAL_SERVER_ERROR, "Error interno del servidor");
    }

    // Guia: valida method not supported includes allow header detail.
    @Test
    void methodNotSupportedIncludesAllowHeaderDetail() {
        ResponseEntity<ApiErrorResponse> response = handler.handleMethodNotSupported(
                new HttpRequestMethodNotSupportedException("POST", List.of("GET", "PATCH")),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().details()).containsEntry(HttpHeaders.ALLOW, "GET, PATCH");
    }

    private void assertResponse(
            ResponseEntity<ApiErrorResponse> response,
            HttpStatus expectedStatus,
            String expectedMessage) {
        assertThat(response.getStatusCode()).isEqualTo(expectedStatus);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().status()).isEqualTo(expectedStatus.value());
        assertThat(response.getBody().message()).isEqualTo(expectedMessage);
        assertThat(response.getBody().path()).isEqualTo("/api/orders");
    }
}
