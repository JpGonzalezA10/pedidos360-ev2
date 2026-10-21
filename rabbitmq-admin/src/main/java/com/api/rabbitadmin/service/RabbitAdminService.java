package com.api.rabbitadmin.service;

import com.api.rabbitadmin.client.RabbitManagementClient;
import com.api.rabbitadmin.config.RabbitAdminProperties;
import com.api.rabbitadmin.dto.AdminResponses.BindingResponse;
import com.api.rabbitadmin.dto.AdminResponses.DeadLetterQueueStatus;
import com.api.rabbitadmin.dto.AdminResponses.DeadLetterSummaryResponse;
import com.api.rabbitadmin.dto.AdminResponses.ExchangeResponse;
import com.api.rabbitadmin.dto.AdminResponses.OperationResponse;
import com.api.rabbitadmin.dto.AdminResponses.QueueResponse;
import com.api.rabbitadmin.dto.AdminResponses.QueuedMessageResponse;
import com.api.rabbitadmin.dto.BindingRequest;
import com.api.rabbitadmin.dto.ExchangeCreateRequest;
import com.api.rabbitadmin.dto.QueueCreateRequest;
import com.api.rabbitadmin.exception.BrokerUnavailableException;
import com.api.rabbitadmin.exception.InvalidConfigurationException;
import com.api.rabbitadmin.exception.ProtectedResourceException;
import com.api.rabbitadmin.exception.ResourceConflictException;
import com.api.rabbitadmin.exception.ResourceNotFoundException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpConnectException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Exchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.ExchangeTypes;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.stereotype.Service;

/*
 * Servicio de administracion de RabbitMQ.
 * Encapsula TODA la logica de creacion, consulta y eliminacion de colas, exchanges y bindings.
 * Los controladores solo llaman a estos metodos de alto nivel y no conocen RabbitAdmin ni la Management API.
 *
 * Reglas aplicadas antes de tocar el broker:
 *  - No se crean ni eliminan recursos reservados (prefijos protegidos como amq.*).
 *  - No se crea un recurso que ya existe (409) ni se opera sobre uno inexistente (404).
 *  - Un DLX informado al crear una cola debe existir (422).
 *  - Un binding solo se crea si el exchange y la cola existen (404).
 */
@Service
public class RabbitAdminService {

    private static final Logger log = LoggerFactory.getLogger(RabbitAdminService.class);
    private static final int MAX_PEEK_MESSAGES = 50;

    private final RabbitAdmin rabbitAdmin;
    private final RabbitManagementClient managementClient;
    private final RabbitAdminProperties properties;

    public RabbitAdminService(
            RabbitAdmin rabbitAdmin,
            RabbitManagementClient managementClient,
            RabbitAdminProperties properties) {
        this.rabbitAdmin = rabbitAdmin;
        this.managementClient = managementClient;
        this.properties = properties;
    }

    // ============================== Colas ==============================

    public List<QueueResponse> listQueues() {
        return managementClient.listQueues().stream()
                .map(this::toQueueResponse)
                .sorted(Comparator.comparing(QueueResponse::name))
                .toList();
    }

    public QueueResponse getQueue(String name) {
        return managementClient.findQueue(name)
                .map(this::toQueueResponse)
                .orElseThrow(() -> new ResourceNotFoundException("La cola '" + name + "' no existe"));
    }

    public QueueResponse createQueue(QueueCreateRequest request) {
        String name = request.name().trim();
        assertNotProtected(name);
        if (managementClient.findQueue(name).isPresent()) {
            throw new ResourceConflictException("La cola '" + name + "' ya existe");
        }

        QueueBuilder builder = request.isDurableOrDefault() ? QueueBuilder.durable(name) : QueueBuilder.nonDurable(name);
        if (request.messageTtlMs() != null) {
            builder.ttl(Math.toIntExact(request.messageTtlMs()));
        }
        if (request.maxLength() != null) {
            builder.maxLength(request.maxLength());
        }
        if (hasText(request.deadLetterExchange())) {
            String dlx = request.deadLetterExchange().trim();
            if (managementClient.findExchange(dlx).isEmpty()) {
                throw new InvalidConfigurationException(
                        "El dead-letter-exchange '" + dlx + "' no existe; creelo antes de asociarlo a la cola");
            }
            builder.deadLetterExchange(dlx);
            if (hasText(request.deadLetterRoutingKey())) {
                builder.deadLetterRoutingKey(request.deadLetterRoutingKey().trim());
            }
        }

        run(() -> rabbitAdmin.declareQueue(builder.build()), "crear la cola " + name);
        log.info("[ADMIN] Cola creada: {} (durable={}, ttl={}, maxLength={}, dlx={})", name,
                request.isDurableOrDefault(), request.messageTtlMs(), request.maxLength(), request.deadLetterExchange());
        return getQueue(name);
    }

    /**
     * Elimina una cola. Con ifUnused/ifEmpty el broker rechaza la eliminacion si tiene consumidores o mensajes,
     * protegiendo colas productivas de un borrado accidental.
     */
    public OperationResponse deleteQueue(String name, boolean ifUnused, boolean ifEmpty) {
        assertNotProtected(name);
        QueueResponse queue = getQueue(name);
        if (ifUnused && queue.consumers() > 0) {
            throw new ResourceConflictException(
                    "La cola '" + name + "' tiene " + queue.consumers() + " consumidor(es) activo(s)");
        }
        if (ifEmpty && queue.messages() > 0) {
            throw new ResourceConflictException("La cola '" + name + "' tiene " + queue.messages() + " mensaje(s)");
        }
        run(() -> rabbitAdmin.deleteQueue(name), "eliminar la cola " + name);
        log.warn("[ADMIN] Cola eliminada: {} (mensajes descartados={})", name, queue.messages());
        return new OperationResponse("Cola eliminada", name, queue.messages());
    }

    public OperationResponse purgeQueue(String name) {
        assertNotProtected(name);
        getQueue(name);
        int purged = call(() -> rabbitAdmin.purgeQueue(name), "purgar la cola " + name);
        log.warn("[ADMIN] Cola purgada: {} (mensajes eliminados={})", name, purged);
        return new OperationResponse("Cola purgada", name, (long) purged);
    }

    /** Inspecciona mensajes sin consumirlos (vuelven a la cola). */
    public List<QueuedMessageResponse> peekMessages(String name, int count) {
        getQueue(name);
        int safeCount = Math.max(1, Math.min(count, MAX_PEEK_MESSAGES));
        return managementClient.peekMessages(name, safeCount).stream()
                .map(this::toQueuedMessage)
                .toList();
    }

    // ============================== Exchanges ==============================

    public List<ExchangeResponse> listExchanges() {
        return managementClient.listExchanges().stream()
                .map(this::toExchangeResponse)
                // El default exchange ("") no se administra por la API.
                .filter(exchange -> !exchange.name().isBlank())
                .sorted(Comparator.comparing(ExchangeResponse::name))
                .toList();
    }

    public ExchangeResponse getExchange(String name) {
        return managementClient.findExchange(name)
                .map(this::toExchangeResponse)
                .orElseThrow(() -> new ResourceNotFoundException("El exchange '" + name + "' no existe"));
    }

    public ExchangeResponse createExchange(ExchangeCreateRequest request) {
        String name = request.name().trim();
        assertNotProtected(name);
        if (managementClient.findExchange(name).isPresent()) {
            throw new ResourceConflictException("El exchange '" + name + "' ya existe");
        }
        String type = request.type().trim().toLowerCase(Locale.ROOT);
        Exchange exchange = new ExchangeBuilder(name, type).durable(request.isDurableOrDefault()).build();
        run(() -> rabbitAdmin.declareExchange(exchange), "crear el exchange " + name);
        log.info("[ADMIN] Exchange creado: {} (type={}, durable={})", name, type, request.isDurableOrDefault());
        return getExchange(name);
    }

    public OperationResponse deleteExchange(String name) {
        assertNotProtected(name);
        getExchange(name);
        run(() -> rabbitAdmin.deleteExchange(name), "eliminar el exchange " + name);
        log.warn("[ADMIN] Exchange eliminado: {}", name);
        return new OperationResponse("Exchange eliminado", name, null);
    }

    // ============================== Bindings ==============================

    public List<BindingResponse> listBindings() {
        return managementClient.listBindings().stream()
                .map(this::toBindingResponse)
                // Los bindings implicitos del default exchange no se muestran.
                .filter(binding -> !binding.exchange().isBlank())
                .sorted(Comparator.comparing(BindingResponse::exchange).thenComparing(BindingResponse::destination))
                .toList();
    }

    public BindingResponse createBinding(BindingRequest request) {
        Binding binding = toBinding(request);
        if (bindingExists(request)) {
            throw new ResourceConflictException("El binding " + describe(request) + " ya existe");
        }
        run(() -> rabbitAdmin.declareBinding(binding), "crear el binding " + describe(request));
        log.info("[ADMIN] Binding creado: {}", describe(request));
        return new BindingResponse(request.exchange(), request.queue(), "queue", request.routingKeyOrEmpty());
    }

    public OperationResponse deleteBinding(BindingRequest request) {
        Binding binding = toBinding(request);
        if (!bindingExists(request)) {
            throw new ResourceNotFoundException("El binding " + describe(request) + " no existe");
        }
        run(() -> rabbitAdmin.removeBinding(binding), "eliminar el binding " + describe(request));
        log.warn("[ADMIN] Binding eliminado: {}", describe(request));
        return new OperationResponse("Binding eliminado", describe(request), null);
    }

    // ============================== Dead Letter Queues ==============================

    /** Resumen de todas las DLQ (colas cuyo nombre termina en el sufijo configurado) para alertas. */
    public DeadLetterSummaryResponse deadLetterSummary() {
        String suffix = properties.deadLetterSuffix();
        List<DeadLetterQueueStatus> queues = listQueues().stream()
                .filter(queue -> queue.name().endsWith(suffix))
                .map(queue -> new DeadLetterQueueStatus(queue.name(), queue.messages(), queue.consumers()))
                .toList();
        long total = queues.stream().mapToLong(DeadLetterQueueStatus::messages).sum();
        return new DeadLetterSummaryResponse(total, total > 0, queues);
    }

    // ============================== Reglas y utilidades ==============================

    private Binding toBinding(BindingRequest request) {
        assertNotProtected(request.exchange());
        assertNotProtected(request.queue());
        getExchange(request.exchange());
        getQueue(request.queue());
        return new Binding(request.queue(), Binding.DestinationType.QUEUE, request.exchange(),
                request.routingKeyOrEmpty(), null);
    }

    private boolean bindingExists(BindingRequest request) {
        return listBindings().stream().anyMatch(binding ->
                binding.exchange().equals(request.exchange())
                        && binding.destination().equals(request.queue())
                        && binding.routingKey().equals(request.routingKeyOrEmpty()));
    }

    private void assertNotProtected(String name) {
        if (name == null || name.isBlank()) {
            throw new InvalidConfigurationException("El nombre del recurso no puede estar vacio");
        }
        for (String prefix : properties.protectedPrefixes()) {
            if (name.startsWith(prefix)) {
                throw new ProtectedResourceException(
                        "El recurso '" + name + "' es reservado (prefijo '" + prefix + "') y no puede modificarse");
            }
        }
    }

    /** Traduce errores AMQP a excepciones del dominio con mensajes claros. */
    private <T> T call(Supplier<T> action, String description) {
        try {
            return action.get();
        } catch (AmqpConnectException exception) {
            throw new BrokerUnavailableException("RabbitMQ no esta disponible para " + description, exception);
        } catch (AmqpException exception) {
            throw new ResourceConflictException("RabbitMQ rechazo " + description + ": " + rootMessage(exception),
                    exception);
        }
    }

    private void run(Runnable action, String description) {
        call(() -> {
            action.run();
            return null;
        }, description);
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage();
    }

    private String describe(BindingRequest request) {
        return request.exchange() + " --[" + request.routingKeyOrEmpty() + "]--> " + request.queue();
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    // ---------- Mapeo de la respuesta JSON de la Management API a DTO ----------

    @SuppressWarnings("unchecked")
    private QueueResponse toQueueResponse(Map<String, Object> json) {
        return new QueueResponse(
                String.valueOf(json.get("name")),
                Boolean.TRUE.equals(json.get("durable")),
                asLong(json.get("messages_ready")),
                asLong(json.get("messages_unacknowledged")),
                asLong(json.get("messages")),
                (int) asLong(json.get("consumers")),
                json.get("arguments") instanceof Map<?, ?> arguments ? (Map<String, Object>) arguments : Map.of());
    }

    private ExchangeResponse toExchangeResponse(Map<String, Object> json) {
        return new ExchangeResponse(
                String.valueOf(json.getOrDefault("name", "")),
                String.valueOf(json.getOrDefault("type", ExchangeTypes.DIRECT)),
                Boolean.TRUE.equals(json.get("durable")),
                Boolean.TRUE.equals(json.get("internal")));
    }

    private BindingResponse toBindingResponse(Map<String, Object> json) {
        return new BindingResponse(
                String.valueOf(json.getOrDefault("source", "")),
                String.valueOf(json.getOrDefault("destination", "")),
                String.valueOf(json.getOrDefault("destination_type", "")),
                String.valueOf(json.getOrDefault("routing_key", "")));
    }

    @SuppressWarnings("unchecked")
    private QueuedMessageResponse toQueuedMessage(Map<String, Object> json) {
        Map<String, Object> props = json.get("properties") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        Map<String, Object> headers = props.get("headers") instanceof Map<?, ?> map
                ? (Map<String, Object>) map : Map.of();
        return new QueuedMessageResponse(
                String.valueOf(json.getOrDefault("exchange", "")),
                String.valueOf(json.getOrDefault("routing_key", "")),
                Boolean.TRUE.equals(json.get("redelivered")),
                String.valueOf(json.getOrDefault("payload", "")),
                headers);
    }

    private long asLong(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }
}
