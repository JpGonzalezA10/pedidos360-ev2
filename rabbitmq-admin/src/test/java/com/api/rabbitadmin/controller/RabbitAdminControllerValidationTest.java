package com.api.rabbitadmin.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.api.rabbitadmin.dto.AdminResponses.OperationResponse;
import com.api.rabbitadmin.dto.AdminResponses.QueueResponse;
import com.api.rabbitadmin.dto.BindingRequest;
import com.api.rabbitadmin.dto.ExchangeCreateRequest;
import com.api.rabbitadmin.dto.QueueCreateRequest;
import com.api.rabbitadmin.exception.GlobalExceptionHandler;
import com.api.rabbitadmin.exception.ProtectedResourceException;
import com.api.rabbitadmin.service.RabbitAdminService;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/*
 * Pruebas de la API REST del administrador: contratos HTTP y validacion de entrada.
 * Se usa MockMvc standalone (sin broker) con el servicio simulado.
 */
class RabbitAdminControllerValidationTest {

    private RabbitAdminService adminService;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        adminService = mock(RabbitAdminService.class);
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        mockMvc = MockMvcBuilders
                .standaloneSetup(
                        new QueueController(adminService),
                        new ExchangeController(adminService),
                        new BindingController(adminService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setValidator(validator)
                .build();
    }

    @Test
    void createQueueReturns201WithLocation() throws Exception {
        when(adminService.createQueue(any(QueueCreateRequest.class)))
                .thenReturn(new QueueResponse("notificaciones.queue", true, 0, 0, 0, 0, Map.of()));

        mockMvc.perform(post("/api/rabbit/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"notificaciones.queue\",\"durable\":true,\"messageTtlMs\":60000}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/rabbit/queues/notificaciones.queue"))
                .andExpect(jsonPath("$.name").value("notificaciones.queue"));
    }

    @Test
    void createQueueRejectsEmptyName() throws Exception {
        mockMvc.perform(post("/api/rabbit/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists());

        verify(adminService, never()).createQueue(any());
    }

    @Test
    void createQueueRejectsInvalidCharactersAndNegativeTtl() throws Exception {
        mockMvc.perform(post("/api/rabbit/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"mi cola!\",\"messageTtlMs\":-5,\"maxLength\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.messageTtlMs").exists())
                .andExpect(jsonPath("$.details.maxLength").exists());
    }

    @Test
    void createQueueRejectsDeadLetterRoutingKeyWithoutExchange() throws Exception {
        mockMvc.perform(post("/api/rabbit/queues")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"ordenes.queue\",\"deadLetterRoutingKey\":\"ordenes.dlq\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.deadLetterConfigurationConsistent").exists());
    }

    @Test
    void createExchangeRejectsUnknownType() throws Exception {
        mockMvc.perform(post("/api/rabbit/exchanges")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"notificaciones.events\",\"type\":\"broadcast\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.type").value("debe ser direct, topic, fanout o headers"));

        verify(adminService, never()).createExchange(any(ExchangeCreateRequest.class));
    }

    @Test
    void createBindingRequiresExchangeAndQueue() throws Exception {
        mockMvc.perform(post("/api/rabbit/bindings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"routingKey\":\"pedido.#\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.exchange").exists())
                .andExpect(jsonPath("$.details.queue").exists());

        verify(adminService, never()).createBinding(any(BindingRequest.class));
    }

    @Test
    void deleteBindingUsesValidatedQueryParams() throws Exception {
        when(adminService.deleteBinding(any(BindingRequest.class)))
                .thenReturn(new OperationResponse("Binding eliminado", "x", null));

        mockMvc.perform(delete("/api/rabbit/bindings")
                        .param("exchange", "pedidos.events")
                        .param("queue", "notificaciones.queue")
                        .param("routingKey", "pedido.#"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Binding eliminado"));
    }

    @Test
    void protectedResourceReturns403() throws Exception {
        when(adminService.deleteExchange("amq.topic"))
                .thenThrow(new ProtectedResourceException("El recurso 'amq.topic' es reservado"));

        mockMvc.perform(delete("/api/rabbit/exchanges/amq.topic"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }
}
