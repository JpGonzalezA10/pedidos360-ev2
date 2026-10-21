package com.api.rabbitadmin.client;

import com.api.rabbitadmin.config.RabbitAdminProperties;
import com.api.rabbitadmin.exception.BrokerUnavailableException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/*
 * Cliente de la RabbitMQ Management HTTP API (http://host:15672/api).
 * AMQP (RabbitAdmin) no permite listar recursos ni leer estadisticas; para eso se usa esta API.
 * Solo RabbitAdminService usa este cliente: los controladores no conocen detalles del broker.
 */
@Component
public class RabbitManagementClient {

    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST_OF_MAPS =
            new ParameterizedTypeReference<>() { };
    private static final ParameterizedTypeReference<Map<String, Object>> MAP =
            new ParameterizedTypeReference<>() { };

    private final RestClient restClient;
    private final String virtualHost;

    public RabbitManagementClient(RestClient rabbitManagementRestClient, RabbitAdminProperties properties) {
        this.restClient = rabbitManagementRestClient;
        this.virtualHost = properties.management().virtualHost();
    }

    public List<Map<String, Object>> listQueues() {
        return getList("/api/queues/{vhost}");
    }

    public Optional<Map<String, Object>> findQueue(String name) {
        return getOptional("/api/queues/{vhost}/{name}", name);
    }

    public List<Map<String, Object>> listExchanges() {
        return getList("/api/exchanges/{vhost}");
    }

    public Optional<Map<String, Object>> findExchange(String name) {
        return getOptional("/api/exchanges/{vhost}/{name}", name);
    }

    public List<Map<String, Object>> listBindings() {
        return getList("/api/bindings/{vhost}");
    }

    /**
     * Lee hasta {@code count} mensajes sin consumirlos (ackmode ack_requeue_true: vuelven a la cola).
     * Util para inspeccionar el contenido y los headers x-death de una DLQ.
     */
    public List<Map<String, Object>> peekMessages(String queue, int count) {
        try {
            List<Map<String, Object>> body = restClient.post()
                    .uri("/api/queues/{vhost}/{name}/get", virtualHost, queue)
                    .body(Map.of(
                            "count", count,
                            "ackmode", "ack_requeue_true",
                            "encoding", "auto",
                            "truncate", 50000))
                    .retrieve()
                    .body(LIST_OF_MAPS);
            return body == null ? List.of() : body;
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    private List<Map<String, Object>> getList(String path) {
        try {
            List<Map<String, Object>> body = restClient.get().uri(path, virtualHost).retrieve().body(LIST_OF_MAPS);
            return body == null ? List.of() : body;
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    private Optional<Map<String, Object>> getOptional(String path, String name) {
        try {
            return Optional.ofNullable(restClient.get().uri(path, virtualHost, name).retrieve().body(MAP));
        } catch (HttpClientErrorException exception) {
            if (exception.getStatusCode() == HttpStatus.NOT_FOUND) {
                return Optional.empty();
            }
            throw unavailable(exception);
        } catch (RestClientException exception) {
            throw unavailable(exception);
        }
    }

    private BrokerUnavailableException unavailable(RestClientException exception) {
        return new BrokerUnavailableException(
                "No fue posible consultar la Management API de RabbitMQ: " + exception.getMessage(), exception);
    }
}
