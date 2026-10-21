package com.api.envios.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/*
 * Pruebas unitarias del filtro interno por API key de envios.
 * Aseguran que solo proteja rutas /api cuando existe clave configurada.
 */
class ApiKeyAuthFilterTest {

    // Guia: valida disabled filter passes request through.
    @Test
    void disabledFilterPassesRequestThrough() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("", "X-API-Key");
        MockHttpServletRequest request = request("/api/shipments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    // Guia: valida configured filter rejects missing api key.
    @Test
    void configuredFilterRejectsMissingApiKey() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", "X-API-Key");
        MockHttpServletRequest request = request("/api/shipments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("API key invalida").contains("/api/shipments");
        verifyNoInteractions(chain);
    }

    // Guia: valida configured filter accepts matching api key.
    @Test
    void configuredFilterAcceptsMatchingApiKey() throws Exception {
        ApiKeyAuthFilter filter = new ApiKeyAuthFilter("secret", "X-API-Key");
        MockHttpServletRequest request = request("/api/shipments");
        request.addHeader("X-API-Key", "secret");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = Mockito.mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    private MockHttpServletRequest request(String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRequestURI(uri);
        return request;
    }
}
