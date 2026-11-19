package com.smartlogix.api_gateway.config;

import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.rewritePath;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.setRequestHeader;
import static org.springframework.cloud.gateway.server.mvc.filter.BeforeFilterFunctions.uri;
import static org.springframework.cloud.gateway.server.mvc.handler.GatewayRouterFunctions.route;
import static org.springframework.cloud.gateway.server.mvc.handler.HandlerFunctions.http;
import static org.springframework.cloud.gateway.server.mvc.predicate.GatewayRequestPredicates.path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.function.RouterFunction;
import org.springframework.web.servlet.function.ServerResponse;

@Configuration
public class KafkaAdminRoutesConfig {

    /*
     * EP5 - External route:
     * /kafka-admin/** -> kafka-admin /api/kafka/**
     *
     * Solo ADMIN (regla en SecurityConfig). El gateway valida el JWT de Azure AD
     * y agrega la API key interna del microservicio administrador.
     */
    @Bean
    @Order(-7)
    public RouterFunction<ServerResponse> kafkaAdminRoute(
            @Value("${gateway.routes.kafka-admin-service-url}") String kafkaAdminServiceUrl,
            @Value("${gateway.routes.kafka-admin-api-key}") String kafkaAdminApiKey) {
        return route("kafka-admin")
                .route(path("/kafka-admin", "/kafka-admin/**"), http())
                .before(uri(kafkaAdminServiceUrl))
                .before(rewritePath("/kafka-admin(?<segment>/?.*)", "/api/kafka${segment}"))
                .before(setRequestHeader("X-API-Key", kafkaAdminApiKey))
                .build();
    }
}
