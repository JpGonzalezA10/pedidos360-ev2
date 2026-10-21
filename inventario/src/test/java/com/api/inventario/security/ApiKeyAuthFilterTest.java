package com.api.inventario.security;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.api.inventario.controller.ProductController;
import com.api.inventario.dto.PageResponse;
import com.api.inventario.dto.ProductResponse;
import com.api.inventario.service.ProductService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/*
 * Pruebas del filtro de API key.
 * Verifican que la seguridad se active cuando existe una clave configurada.
 */
@WebMvcTest(ProductController.class)
@Import(ApiKeyAuthFilter.class)
@TestPropertySource(properties = "inventory.security.api-key=test-key")
class ApiKeyAuthFilterTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ProductService productService;

    // Guia: valida rejects missing api key.
    @Test
    void rejectsMissingApiKey() throws Exception {
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("API key invalida o ausente"));
    }

    // Guia: valida rejects invalid api key.
    @Test
    void rejectsInvalidApiKey() throws Exception {
        mockMvc.perform(get("/api/products")
                        .header("X-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("API key invalida o ausente"));
    }

    // Guia: valida accepts valid api key.
    @Test
    void acceptsValidApiKey() throws Exception {
        when(productService.searchActive(null, null, null, 0, 20))
                .thenReturn(new PageResponse<ProductResponse>(List.of(), 0, 20, 0, 0, true, true));

        mockMvc.perform(get("/api/products")
                        .header("X-API-Key", "test-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").isArray());
    }
}

