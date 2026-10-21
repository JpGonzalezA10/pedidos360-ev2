package com.api.inventario.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.api.inventario.dto.ProductCreateRequest;
import com.api.inventario.exception.BusinessRuleException;
import com.api.inventario.factory.SkuFactory;
import com.api.inventario.model.Product;
import com.api.inventario.repository.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/*
 * Pruebas unitarias de ProductService.
 * Se mockea ProductRepository para validar reglas de negocio sin acceder a la BD.
 */
@ExtendWith(MockitoExtension.class)
class ProductServiceTest {

    @Mock
    private ProductRepository productRepository;

    @Mock
    private SkuFactory skuFactory;

    @InjectMocks
    private ProductService productService;

    // Guia: valida create stores valid product.
    @Test
    void createStoresValidProduct() {
        // Verifica normalizacion del SKU y mapeo del request hacia la entidad.
        ProductCreateRequest request = new ProductCreateRequest(
                " slx-999 ",
                "Producto test",
                "Descripcion",
                BigDecimal.valueOf(12990),
                "Categoria");
        when(productRepository.existsBySku("SLX-999")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        productService.create(request);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());
        Product product = productCaptor.getValue();
        assertThat(product.getSku()).isEqualTo("SLX-999");
        assertThat(product.getName()).isEqualTo("Producto test");
        assertThat(product.isActive()).isTrue();
    }

    // Guia: valida create rejects duplicated sku.
    @Test
    void createRejectsDuplicatedSku() {
        // Verifica que no se permitan dos productos con el mismo SKU.
        ProductCreateRequest request = new ProductCreateRequest(
                "SLX-001",
                "Producto test",
                null,
                BigDecimal.TEN,
                null);
        when(productRepository.existsBySku("SLX-001")).thenReturn(true);

        assertThatThrownBy(() -> productService.create(request))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("Ya existe un producto con SKU SLX-001");
    }

    // Guia: valida create generates sku when request does not provide one.
    @Test
    void createGeneratesSkuWhenRequestDoesNotProvideOne() {
        // Verifica que el alta pueda delegar el SKU automatico en la factory.
        ProductCreateRequest request = new ProductCreateRequest(
                null,
                "Producto test",
                null,
                BigDecimal.TEN,
                null);
        when(productRepository.findSkusByPrefix("SKU-")).thenReturn(List.of("SKU-000001"));
        when(skuFactory.nextSku("SKU-", List.of("SKU-000001"))).thenReturn("SKU-000002");
        when(productRepository.existsBySku("SKU-000002")).thenReturn(false);
        when(productRepository.save(any(Product.class))).thenAnswer(invocation -> invocation.getArgument(0));

        productService.create(request);

        ArgumentCaptor<Product> productCaptor = ArgumentCaptor.forClass(Product.class);
        verify(productRepository).save(productCaptor.capture());
        assertThat(productCaptor.getValue().getSku()).isEqualTo("SKU-000002");
    }

    // Guia: valida soft delete marks product inactive.
    @Test
    void softDeleteMarksProductInactive() {
        // Verifica baja logica: el producto queda inactivo, no eliminado fisicamente.
        UUID productId = UUID.randomUUID();
        Product product = new Product();
        product.setId(productId);
        product.setActive(true);
        when(productRepository.findById(productId)).thenReturn(Optional.of(product));

        productService.softDelete(productId);

        assertThat(product.isActive()).isFalse();
        verify(productRepository).save(product);
    }
}
