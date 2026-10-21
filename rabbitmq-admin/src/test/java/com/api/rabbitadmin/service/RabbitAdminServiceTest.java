package com.api.rabbitadmin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.api.rabbitadmin.client.RabbitManagementClient;
import com.api.rabbitadmin.config.RabbitAdminProperties;
import com.api.rabbitadmin.dto.AdminResponses.DeadLetterSummaryResponse;
import com.api.rabbitadmin.dto.BindingRequest;
import com.api.rabbitadmin.dto.QueueCreateRequest;
import com.api.rabbitadmin.exception.InvalidConfigurationException;
import com.api.rabbitadmin.exception.ProtectedResourceException;
import com.api.rabbitadmin.exception.ResourceConflictException;
import com.api.rabbitadmin.exception.ResourceNotFoundException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitAdmin;

/*
 * Pruebas de reglas del servicio administrador sin broker real.
 */
class RabbitAdminServiceTest {

    private RabbitAdmin rabbitAdmin;
    private RabbitManagementClient managementClient;
    private RabbitAdminService service;

    @BeforeEach
    void setUp() {
        rabbitAdmin = mock(RabbitAdmin.class);
        managementClient = mock(RabbitManagementClient.class);
        RabbitAdminProperties properties = new RabbitAdminProperties(
                new RabbitAdminProperties.Management("http://localhost:15672", "guest", "guest", "/"),
                List.of("amq."),
                ".dlq");
        service = new RabbitAdminService(rabbitAdmin, managementClient, properties);
    }

    @Test
    void createQueueDeclaresQueueWithDeadLetterArguments() {
        when(managementClient.findQueue("notificaciones.queue"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(queueJson("notificaciones.queue", 0, 0)));
        when(managementClient.findExchange("notificaciones.dlx")).thenReturn(Optional.of(Map.of("name", "x")));

        service.createQueue(new QueueCreateRequest(
                "notificaciones.queue", true, 60000L, 500, "notificaciones.dlx", "notificaciones.dlq"));

        ArgumentCaptor<Queue> captor = ArgumentCaptor.forClass(Queue.class);
        verify(rabbitAdmin).declareQueue(captor.capture());
        Queue declared = captor.getValue();
        assertThat(declared.isDurable()).isTrue();
        assertThat(declared.getArguments())
                .containsEntry("x-message-ttl", 60000)
                .containsEntry("x-max-length", 500L)
                .containsEntry("x-dead-letter-exchange", "notificaciones.dlx")
                .containsEntry("x-dead-letter-routing-key", "notificaciones.dlq");
    }

    @Test
    void createQueueFailsWhenItAlreadyExists() {
        when(managementClient.findQueue("pedidos.envio-estado.queue"))
                .thenReturn(Optional.of(queueJson("pedidos.envio-estado.queue", 0, 1)));

        assertThatThrownBy(() -> service.createQueue(
                new QueueCreateRequest("pedidos.envio-estado.queue", true, null, null, null, null)))
                .isInstanceOf(ResourceConflictException.class);
        verify(rabbitAdmin, never()).declareQueue(any(Queue.class));
    }

    @Test
    void createQueueFailsWhenDeadLetterExchangeDoesNotExist() {
        when(managementClient.findQueue("x.queue")).thenReturn(Optional.empty());
        when(managementClient.findExchange("no.existe")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createQueue(
                new QueueCreateRequest("x.queue", true, null, null, "no.existe", null)))
                .isInstanceOf(InvalidConfigurationException.class)
                .hasMessageContaining("no.existe");
    }

    @Test
    void reservedResourcesCannotBeModified() {
        assertThatThrownBy(() -> service.deleteExchange("amq.direct"))
                .isInstanceOf(ProtectedResourceException.class);
        verify(rabbitAdmin, never()).deleteExchange(any());
    }

    @Test
    void deleteQueueIfUnusedRejectsQueueWithConsumers() {
        when(managementClient.findQueue("envios.pedido-confirmado.queue"))
                .thenReturn(Optional.of(queueJson("envios.pedido-confirmado.queue", 0, 1)));

        assertThatThrownBy(() -> service.deleteQueue("envios.pedido-confirmado.queue", true, false))
                .isInstanceOf(ResourceConflictException.class)
                .hasMessageContaining("consumidor");
        verify(rabbitAdmin, never()).deleteQueue(any(String.class));
    }

    @Test
    void createBindingFailsWhenQueueDoesNotExist() {
        when(managementClient.findExchange("pedidos.events")).thenReturn(Optional.of(Map.of("name", "pedidos.events")));
        when(managementClient.findQueue("fantasma.queue")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.createBinding(
                new BindingRequest("pedidos.events", "fantasma.queue", "pedido.#")))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void deadLetterSummaryAlertsWhenAnyDlqHasMessages() {
        when(managementClient.listQueues()).thenReturn(List.of(
                queueJson("pedidos.envio-estado.queue", 4, 1),
                queueJson("pedidos.envio-estado.dlq", 2, 0),
                queueJson("envios.pedido-confirmado.dlq", 0, 0)));

        DeadLetterSummaryResponse summary = service.deadLetterSummary();

        assertThat(summary.alert()).isTrue();
        assertThat(summary.totalMessages()).isEqualTo(2);
        assertThat(summary.queues()).hasSize(2);
    }

    private Map<String, Object> queueJson(String name, long messages, int consumers) {
        return Map.of(
                "name", name,
                "durable", true,
                "messages", messages,
                "messages_ready", messages,
                "messages_unacknowledged", 0,
                "consumers", consumers,
                "arguments", Map.of());
    }
}
