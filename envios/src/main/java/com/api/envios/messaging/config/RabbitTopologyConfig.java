package com.api.envios.messaging.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/*
 * Topologia RabbitMQ del microservicio envios (Queue, Exchange y Binding como beans).
 *
 * Rutas de mensajeria:
 *  1) PUBLICA en envios.events (topic):
 *       envio.creado, envio.estado.actualizado -> pedidos sincroniza el estado del pedido.
 *  2) CONSUME desde pedidos.events (topic):
 *       pedido.confirmado -> envios.pedido-confirmado.queue (crea el despacho automaticamente)
 *       pedido.cancelado  -> envios.pedido-cancelado.queue  (cancela el despacho pendiente)
 *  3) RUTA DE EXCEPCION (por cada cola):
 *       cola --(nack sin requeue)--> envios.dlx --> <cola>.dlq
 *       <cola>.retry (TTL) --(expira)--> vuelve a la cola principal
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitTopologyConfig {

    private final MessagingProperties properties;

    public RabbitTopologyConfig(MessagingProperties properties) {
        this.properties = properties;
    }

    // ===================== Exchanges =====================

    /** Exchange propio donde envios publica sus eventos. */
    @Bean
    public TopicExchange enviosExchange() {
        return ExchangeBuilder.topicExchange(properties.exchanges().envios()).durable(true).build();
    }

    /** Exchange de pedidos; se declara igual que en pedidos para poder enlazar las colas. */
    @Bean
    public TopicExchange pedidosExchange() {
        return ExchangeBuilder.topicExchange(properties.exchanges().pedidos()).durable(true).build();
    }

    /** Dead Letter Exchange (direct) del microservicio envios. */
    @Bean
    public DirectExchange enviosDeadLetterExchange() {
        return ExchangeBuilder.directExchange(properties.exchanges().deadLetter()).durable(true).build();
    }

    // ============ Caso de uso 1: pedido confirmado -> crear envio ============

    @Bean
    public Queue pedidoConfirmadoQueue() {
        return mainQueue(properties.queues().pedidoConfirmado(), properties.queues().pedidoConfirmadoDlq());
    }

    @Bean
    public Queue pedidoConfirmadoRetryQueue() {
        return retryQueue(properties.queues().pedidoConfirmadoRetry(), properties.queues().pedidoConfirmado());
    }

    @Bean
    public Queue pedidoConfirmadoDlq() {
        return deadLetterQueue(properties.queues().pedidoConfirmadoDlq());
    }

    /** pedidos.events --pedido.confirmado--> envios.pedido-confirmado.queue */
    @Bean
    public Binding pedidoConfirmadoBinding() {
        return BindingBuilder.bind(pedidoConfirmadoQueue())
                .to(pedidosExchange())
                .with(properties.routingKeys().pedidoConfirmado());
    }

    /** envios.dlx --envios.pedido-confirmado.dlq--> envios.pedido-confirmado.dlq */
    @Bean
    public Binding pedidoConfirmadoDlqBinding() {
        return BindingBuilder.bind(pedidoConfirmadoDlq())
                .to(enviosDeadLetterExchange())
                .with(properties.queues().pedidoConfirmadoDlq());
    }

    // ============ Caso de uso 2: pedido cancelado -> cancelar envio ============

    @Bean
    public Queue pedidoCanceladoQueue() {
        return mainQueue(properties.queues().pedidoCancelado(), properties.queues().pedidoCanceladoDlq());
    }

    @Bean
    public Queue pedidoCanceladoRetryQueue() {
        return retryQueue(properties.queues().pedidoCanceladoRetry(), properties.queues().pedidoCancelado());
    }

    @Bean
    public Queue pedidoCanceladoDlq() {
        return deadLetterQueue(properties.queues().pedidoCanceladoDlq());
    }

    /** pedidos.events --pedido.cancelado--> envios.pedido-cancelado.queue */
    @Bean
    public Binding pedidoCanceladoBinding() {
        return BindingBuilder.bind(pedidoCanceladoQueue())
                .to(pedidosExchange())
                .with(properties.routingKeys().pedidoCancelado());
    }

    /** envios.dlx --envios.pedido-cancelado.dlq--> envios.pedido-cancelado.dlq */
    @Bean
    public Binding pedidoCanceladoDlqBinding() {
        return BindingBuilder.bind(pedidoCanceladoDlq())
                .to(enviosDeadLetterExchange())
                .with(properties.queues().pedidoCanceladoDlq());
    }

    // ===================== Fabricas de colas =====================

    /** Cola durable que, al rechazar sin requeue, deriva el mensaje al DLX con la routing key de su DLQ. */
    private Queue mainQueue(String name, String dlqName) {
        return QueueBuilder.durable(name)
                .deadLetterExchange(properties.exchanges().deadLetter())
                .deadLetterRoutingKey(dlqName)
                .build();
    }

    /** Cola de espera: al expirar el TTL devuelve el mensaje a la cola principal (default exchange). */
    private Queue retryQueue(String name, String mainQueueName) {
        return QueueBuilder.durable(name)
                .ttl((int) properties.retry().delayMs())
                .deadLetterExchange("")
                .deadLetterRoutingKey(mainQueueName)
                .build();
    }

    /** DLQ con politica de retencion (TTL y largo maximo). */
    private Queue deadLetterQueue(String name) {
        return QueueBuilder.durable(name)
                .ttl((int) properties.dlq().retentionMs())
                .maxLength(properties.dlq().maxLength())
                .build();
    }
}
