package com.api.kafkaadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/*
 * Microservicio administrador de Apache Kafka (EP5).
 * Expone una API REST para inspeccionar el cluster, crear, modificar y eliminar topicos y sus
 * configuraciones, administrar consumer groups (lag, reset de offsets, eliminacion) y
 * monitorear/reprocesar las Dead Letter Topics de Pedidos360.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class KafkaAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(KafkaAdminApplication.class, args);
    }
}
