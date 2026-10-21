package com.api.inventario.messaging.config;

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
 * Topologia RabbitMQ del microservicio inventario (Queue, Exchange y Binding como beans).
 *
 * Rutas de mensajeria:
 *  1) CONSUME desde pedidos.events (topic):
 *       pedido.confirmado -> inventario.pedido-confirmado.queue (evalua stock bajo de los productos)
 *  2) PUBLICA en inventario.events (topic):
 *       inventario.stock.bajo -> inventario.alertas-stock.queue (notificacion al operador)
 *  3) RUTA DE EXCEPCION (por cada cola):
 *       cola --(nack sin requeue)--> inventario.dlx --> <cola>.dlq
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

    /** Exchange propio donde inventario publica sus eventos (alertas de stock). */
    @Bean
    public TopicExchange inventarioExchange() {
        return ExchangeBuilder.topicExchange(properties.exchanges().inventario()).durable(true).build();
    }

    /** Exchange de pedidos; se declara igual que en pedidos para poder enlazar la cola. */
    @Bean
    public TopicExchange pedidosExchange() {
        return ExchangeBuilder.topicExchange(properties.exchanges().pedidos()).durable(true).build();
    }

    /** Dead Letter Exchange (direct) del microservicio inventario. */
    @Bean
    public DirectExchange inventarioDeadLetterExchange() {
        return ExchangeBuilder.directExchange(properties.exchanges().deadLetter()).durable(true).build();
    }

    // ============ Caso de uso 1: pedido confirmado -> evaluar stock bajo ============

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

    /** pedidos.events --pedido.confirmado--> inventario.pedido-confirmado.queue */
    @Bean
    public Binding pedidoConfirmadoBinding() {
        return BindingBuilder.bind(pedidoConfirmadoQueue())
                .to(pedidosExchange())
                .with(properties.routingKeys().pedidoConfirmado());
    }

    /** inventario.dlx --inventario.pedido-confirmado.dlq--> inventario.pedido-confirmado.dlq */
    @Bean
    public Binding pedidoConfirmadoDlqBinding() {
        return BindingBuilder.bind(pedidoConfirmadoDlq())
                .to(inventarioDeadLetterExchange())
                .with(properties.queues().pedidoConfirmadoDlq());
    }

    // ============ Caso de uso 2: stock bajo -> notificacion al operador ============

    @Bean
    public Queue alertasStockQueue() {
        return mainQueue(properties.queues().alertasStock(), properties.queues().alertasStockDlq());
    }

    @Bean
    public Queue alertasStockRetryQueue() {
        return retryQueue(properties.queues().alertasStockRetry(), properties.queues().alertasStock());
    }

    @Bean
    public Queue alertasStockDlq() {
        return deadLetterQueue(properties.queues().alertasStockDlq());
    }

    /** inventario.events --inventario.stock.bajo--> inventario.alertas-stock.queue */
    @Bean
    public Binding alertasStockBinding() {
        return BindingBuilder.bind(alertasStockQueue())
                .to(inventarioExchange())
                .with(properties.routingKeys().stockBajo());
    }

    /** inventario.dlx --inventario.alertas-stock.dlq--> inventario.alertas-stock.dlq */
    @Bean
    public Binding alertasStockDlqBinding() {
        return BindingBuilder.bind(alertasStockDlq())
                .to(inventarioDeadLetterExchange())
                .with(properties.queues().alertasStockDlq());
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
