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
public class RabbitAdminRoutesConfig {

    /*
     * EP3 - External route:
     * /rabbit-admin/** -> rabbitmq-admin /api/rabbit/**
     *
     * Solo ADMIN (regla en SecurityConfig). El gateway valida el JWT de Azure AD
     * y agrega la API key interna del microservicio administrador.
     */
    @Bean
    @Order(-6)
    public RouterFunction<ServerResponse> rabbitAdminRoute(
            @Value("${gateway.routes.rabbit-admin-service-url}") String rabbitAdminServiceUrl,
            @Value("${gateway.routes.rabbit-admin-api-key}") String rabbitAdminApiKey) {
        return route("rabbit-admin")
                .route(path("/rabbit-admin", "/rabbit-admin/**"), http())
                .before(uri(rabbitAdminServiceUrl))
                .before(rewritePath("/rabbit-admin(?<segment>/?.*)", "/api/rabbit${segment}"))
                .before(setRequestHeader("X-API-Key", rabbitAdminApiKey))
                .build();
    }
}
