package com.api.kafkaadmin.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;

/*
 * Infraestructura del administrador: un AdminClient de Kafka reutilizable creado con la misma
 * configuracion (bootstrap servers, client-id, timeouts) del KafkaAdmin autoconfigurado por Spring Boot.
 * Se cierra al detener la aplicacion (destroyMethod = close).
 */
@Configuration
public class KafkaAdminClientConfig {

    @Bean(destroyMethod = "close")
    public AdminClient kafkaAdminClient(KafkaAdmin kafkaAdmin) {
        return AdminClient.create(kafkaAdmin.getConfigurationProperties());
    }
}
