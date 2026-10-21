package com.api.rabbitadmin;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/*
 * Microservicio administrador de RabbitMQ (EP3).
 * Expone una API REST para crear, consultar y eliminar colas, exchanges y bindings,
 * y para monitorear las Dead Letter Queues del sistema Pedidos360.
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class RabbitmqAdminApplication {

    public static void main(String[] args) {
        SpringApplication.run(RabbitmqAdminApplication.class, args);
    }
}
