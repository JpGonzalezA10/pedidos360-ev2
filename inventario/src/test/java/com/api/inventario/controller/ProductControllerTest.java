package com.api.inventario.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.api.inventario.dto.PageResponse;
import com.api.inventario.dto.ProductCreateRequest;
import com.api.inventario.dto.ProductResponse;
import com.api.inventario.dto.ProductSkuResponse;
import com.api.inventario.dto.ProductUpdateRequest;
import com.api.inventario.exception.ResourceNotFoundException;
import com.api.inventario.service.ProductService;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

/*
 * Pruebas web del ProductController.
 * Usan MockMvc para probar rutas HTTP sin levantar un servidor real y Mockito
 * para aislar el controller de la logica del service.
 */
@WebMvcTest(ProductController.class)
class ProductControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ProductService productService;

    // Guia: valida find all returns active products.
    @Test
    void findAllReturnsActiveProducts() throws Exception {
        // Verifica que GET /api/products responda una pagina JSON de productos.
        UUID productId = UUID.randomUUID();
        ProductResponse response = productResponse(productId, "SLX-001", "Producto test");
        when(productService.searchActive(null, null, null, 0, 20))
                .thenReturn(pageResponse(List.of(response)));

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(productId.toString()))
                .andExpect(jsonPath("$.content[0].sku").value("SLX-001"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    // Guia: valida find all passes filters and pagination.
    @Test
    void findAllPassesFiltersAndPagination() throws Exception {
        // Verifica filtros de busqueda y paginacion del listado de productos.
        ProductResponse response = productResponse(UUID.randomUUID(), "SLX-999", "Producto test");
        when(productService.searchActive("SLX", "Producto", "Categoria", 1, 5))
                .thenReturn(pageResponse(List.of(response)));

        mockMvc.perform(get("/api/products")
                        .param("sku", "SLX")
                        .param("name", "Producto")
                        .param("category", "Categoria")
                        .param("page", "1")
                        .param("size", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].sku").value("SLX-999"))
                .andExpect(jsonPath("$.page").value(0));
    }

    // Guia: valida get product returns not found.
    @Test
    void getProductReturnsNotFound() throws Exception {
        // Verifica que una excepcion del service se traduzca a HTTP 404.
        UUID productId = UUID.randomUUID();
        when(productService.findById(productId))
                .thenThrow(new ResourceNotFoundException("Producto no encontrado con id " + productId));

        mockMvc.perform(get("/api/products/{id}", productId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Producto no encontrado con id " + productId));
    }

    // Guia: valida find product by sku returns product.
    @Test
    void findProductBySkuReturnsProduct() throws Exception {
        // Verifica busqueda por SKU usando una ruta distinta a la busqueda por UUID.
        UUID productId = UUID.randomUUID();
        ProductResponse response = productResponse(productId, "SLX-999", "Producto test");
        when(productService.findBySku("SLX-999")).thenReturn(response);

        mockMvc.perform(get("/api/products/sku/{sku}", "SLX-999"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.sku").value("SLX-999"));
    }

    // Guia: valida get next sku returns suggested sku.
    @Test
    void getNextSkuReturnsSuggestedSku() throws Exception {
        // Verifica el endpoint usado por el frontend para mostrar el proximo SKU.
        when(productService.getNextSku()).thenReturn(new ProductSkuResponse("SKU-000001"));

        mockMvc.perform(get("/api/products/next-sku"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sku").value("SKU-000001"));
    }

    // Guia: valida create product returns created.
    @Test
    void createProductReturnsCreated() throws Exception {
        // Verifica que el alta de producto responda 201 Created y devuelva el body.
        UUID productId = UUID.randomUUID();
        ProductCreateRequest request = new ProductCreateRequest(
                "SLX-999",
                "Producto test",
                null,
                BigDecimal.valueOf(12990),
                "Categoria");
        ProductResponse response = productResponse(productId, "SLX-999", "Producto test");
        when(productService.create(any(ProductCreateRequest.class))).thenReturn(response);

        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.sku").value("SLX-999"));
    }

    // Guia: valida create product rejects invalid payload.
    @Test
    void createProductRejectsInvalidPayload() throws Exception {
        // Verifica validaciones del DTO antes de llamar a la capa de negocio.
        String payload = """
                {
                  "sku": "",
                  "name": "",
                  "unitPrice": -1
                }
                """;

        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("La solicitud contiene datos invalidos"))
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.unitPrice").exists());
    }

    // Guia: valida get product rejects invalid uuid.
    @Test
    void getProductRejectsInvalidUuid() throws Exception {
        // Verifica que UUID invalido mantenga el formato JSON comun de errores.
        mockMvc.perform(get("/api/products/{id}", "no-es-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Parametro invalido"))
                .andExpect(jsonPath("$.details.id").exists());
    }

    // Guia: valida create product rejects malformed json.
    @Test
    void createProductRejectsMalformedJson() throws Exception {
        // Verifica que JSON mal formado no exponga errores internos.
        String payload = """
                {
                  "sku": "SLX-999",
                  "name": "Producto test",
                }
                """;

        mockMvc.perform(post("/api/products")
                        .contentType("application/json")
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("JSON de solicitud invalido o mal formado"));
    }

    // Guia: valida update product returns updated values.
    @Test
    void updateProductReturnsUpdatedValues() throws Exception {
        // Verifica que PUT /api/products/{id} responda los valores actualizados.
        UUID productId = UUID.randomUUID();
        ProductUpdateRequest request = new ProductUpdateRequest(
                "SLX-999",
                "Producto actualizado",
                "Nueva descripcion",
                BigDecimal.valueOf(13990),
                "Categoria",
                true);
        ProductResponse response = productResponse(productId, "SLX-999", "Producto actualizado");
        when(productService.update(eq(productId), any(ProductUpdateRequest.class))).thenReturn(response);

        mockMvc.perform(put("/api/products/{id}", productId)
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(productId.toString()))
                .andExpect(jsonPath("$.name").value("Producto actualizado"));
    }

    // Guia: valida delete product returns no content.
    @Test
    void deleteProductReturnsNoContent() throws Exception {
        // Verifica baja logica expuesta como HTTP 204 No Content.
        UUID productId = UUID.randomUUID();

        mockMvc.perform(delete("/api/products/{id}", productId))
                .andExpect(status().isNoContent());

        verify(productService).softDelete(productId);
    }

    private ProductResponse productResponse(UUID productId, String sku, String name) {
        // Helper para no repetir la construccion de respuestas en cada prueba.
        return new ProductResponse(
                productId,
                sku,
                name,
                null,
                BigDecimal.valueOf(12990),
                "Categoria",
                true,
                null,
                null);
    }

    private PageResponse<ProductResponse> pageResponse(List<ProductResponse> products) {
        // Helper para simular respuestas paginadas del service.
        return new PageResponse<>(
                products,
                0,
                20,
                products.size(),
                1,
                true,
                true);
    }
}
