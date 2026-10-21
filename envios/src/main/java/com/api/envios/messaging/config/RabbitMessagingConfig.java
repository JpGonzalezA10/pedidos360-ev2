package com.api.envios.messaging.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.boot.amqp.autoconfigure.SimpleRabbitListenerContainerFactoryConfigurer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * Infraestructura comun de mensajeria: conversion JSON y RabbitTemplate.
 * - Publisher confirms: el broker confirma (ack/nack) cada publicacion.
 * - Mandatory + returns: si un mensaje no tiene cola destino, vuelve al productor y se registra.
 * - Listeners: reciben el mensaje AMQP sin convertir; cada consumidor lo convierte a su propio contrato.
 */
@Configuration
public class RabbitMessagingConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitMessagingConfig.class);

    /** Los eventos viajan como JSON; el consumidor indica el tipo esperado al convertir. */
    @Bean
    public JacksonJsonMessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }

    @Bean
    public RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter jsonMessageConverter) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(jsonMessageConverter);
        template.setMandatory(true);
        template.setConfirmCallback((correlation, ack, cause) -> {
            String id = correlation != null ? correlation.getId() : "sin-id";
            if (ack) {
                log.debug("[PUBLISH-CONFIRM] Broker confirmo el mensaje {}", id);
            } else {
                log.error("[PUBLISH-NACK] Broker rechazo el mensaje {}: {}", id, cause);
            }
        });
        template.setReturnsCallback(returned -> log.error(
                "[PUBLISH-RETURNED] Mensaje sin cola destino. exchange={} routingKey={} replyText={}",
                returned.getExchange(), returned.getRoutingKey(), returned.getReplyText()));
        return template;
    }

    /**
     * Fabrica de listeners (reemplaza la de Spring Boot, conservando ACK manual y prefetch del properties).
     * Entrega el mensaje sin convertir: el header __TypeId__ trae clases de otro microservicio que aqui
     * no existen, por eso cada consumidor convierte el JSON a su contrato local (ver consumer/*).
     */
    @Bean
    public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            SimpleRabbitListenerContainerFactoryConfigurer configurer, ConnectionFactory connectionFactory) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        configurer.configure(factory, connectionFactory);
        factory.setMessageConverter(new SimpleMessageConverter());
        return factory;
    }
}
