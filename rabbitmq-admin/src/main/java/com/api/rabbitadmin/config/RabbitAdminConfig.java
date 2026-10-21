package com.api.rabbitadmin.config;

import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/*
 * Beans de infraestructura del administrador. Ninguna clase de negocio crea conexiones.
 *  - RabbitAdmin: declara y elimina colas, exchanges y bindings por AMQP.
 *  - RestClient: cliente de la Management HTTP API (puerto 15672) para listar recursos.
 */
@Configuration
public class RabbitAdminConfig {

    @Bean
    public RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        RabbitAdmin admin = new RabbitAdmin(connectionFactory);
        // Este servicio no declara topologia propia al iniciar; solo opera bajo demanda.
        admin.setAutoStartup(false);
        return admin;
    }

    @Bean
    public RestClient rabbitManagementRestClient(RestClient.Builder builder, RabbitAdminProperties properties) {
        RabbitAdminProperties.Management management = properties.management();
        return builder
                .baseUrl(management.url())
                .defaultHeaders(headers -> headers.setBasicAuth(management.username(), management.password()))
                .defaultHeader(HttpHeaders.ACCEPT, "application/json")
                .build();
    }
}
