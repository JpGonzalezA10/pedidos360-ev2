package com.api.pedidos.messaging.config;

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
 * Topologia RabbitMQ del microservicio pedidos (Queue, Exchange y Binding como beans).
 *
 * Rutas de mensajeria:
 *  1) PUBLICA en pedidos.events (topic):
 *       pedido.confirmado          -> lo consume envios para crear el despacho
 *                                     e inventario para revisar stock bajo.
 *       pedido.cancelado           -> lo consume envios para cancelar el despacho.
 *       pedido.estado.actualizado  -> pedidos.notificaciones-cliente.queue (notifica al cliente).
 *  2) CONSUME desde envios.events (topic):
 *       envio.estado.actualizado   -> pedidos.envio-estado.queue
 *  3) RUTA DE EXCEPCION:
 *       pedidos.envio-estado.queue --(nack sin requeue)--> pedidos.dlx --> pedidos.envio-estado.dlq
 *       pedidos.envio-estado.retry (TTL) --(expira)--> vuelve a pedidos.envio-estado.queue
 */
@Configuration
@EnableConfigurationProperties(MessagingProperties.class)
public class RabbitTopologyConfig {

    private final MessagingProperties properties;

    public RabbitTopologyConfig(MessagingProperties properties) {
        this.properties = properties;
    }

    // ===================== Exchanges =====================

    /** Exchange propio donde pedidos publica sus eventos de dominio. */
    @Bean
    public TopicExchange pedidosExchange() {
        return ExchangeBuilder.topicExchange(properties.exchanges().pedidos()).durable(true).build();
    }

    /** Exchange de envios; se declara igual que en envios para poder enlazar la cola. */
    @Bean
    public TopicExchange enviosExchange() {
        return ExchangeBuilder.topicExchange(properties.exchanges().envios()).durable(true).build();
    }

    /** Dead Letter Exchange (direct) para enrutar mensajes rechazados hacia su DLQ. */
    @Bean
    public DirectExchange pedidosDeadLetterExchange() {
        return ExchangeBuilder.directExchange(properties.exchanges().deadLetter()).durable(true).build();
    }

    // ===================== Colas =====================

    /** Cola principal: recibe cambios de estado de envio. Si se rechaza sin requeue va al DLX. */
    @Bean
    public Queue envioEstadoQueue() {
        MessagingProperties.Queues queues = properties.queues();
        return QueueBuilder.durable(queues.envioEstado())
                .deadLetterExchange(properties.exchanges().deadLetter())
                .deadLetterRoutingKey(queues.envioEstadoDlq())
                .build();
    }

    /**
     * Cola de reintento diferido: el consumidor deja aqui los mensajes con error transitorio.
     * Al expirar el TTL, RabbitMQ los devuelve a la cola principal (default exchange).
     */
    @Bean
    public Queue envioEstadoRetryQueue() {
        MessagingProperties.Queues queues = properties.queues();
        return QueueBuilder.durable(queues.envioEstadoRetry())
                .ttl((int) properties.retry().delayMs())
                .deadLetterExchange("")
                .deadLetterRoutingKey(queues.envioEstado())
                .build();
    }

    /** DLQ con politica de retencion (TTL y largo maximo) para no crecer sin limite. */
    @Bean
    public Queue envioEstadoDlq() {
        return QueueBuilder.durable(properties.queues().envioEstadoDlq())
                .ttl((int) properties.dlq().retentionMs())
                .maxLength(properties.dlq().maxLength())
                .build();
    }

    // ===================== Bindings =====================

    /** envios.events --envio.estado.actualizado--> pedidos.envio-estado.queue */
    @Bean
    public Binding envioEstadoBinding() {
        return BindingBuilder.bind(envioEstadoQueue())
                .to(enviosExchange())
                .with(properties.routingKeys().envioEstadoActualizado());
    }

    /** pedidos.dlx --pedidos.envio-estado.dlq--> pedidos.envio-estado.dlq */
    @Bean
    public Binding envioEstadoDlqBinding() {
        return BindingBuilder.bind(envioEstadoDlq())
                .to(pedidosDeadLetterExchange())
                .with(properties.queues().envioEstadoDlq());
    }

    // ============ Caso de uso: pedido.estado.actualizado -> notificacion al cliente ============

    /** Cola de notificaciones al cliente por cada cambio de estado de su pedido. */
    @Bean
    public Queue notificacionesClienteQueue() {
        MessagingProperties.Queues queues = properties.queues();
        return QueueBuilder.durable(queues.notificacionesCliente())
                .deadLetterExchange(properties.exchanges().deadLetter())
                .deadLetterRoutingKey(queues.notificacionesClienteDlq())
                .build();
    }

    @Bean
    public Queue notificacionesClienteRetryQueue() {
        MessagingProperties.Queues queues = properties.queues();
        return QueueBuilder.durable(queues.notificacionesClienteRetry())
                .ttl((int) properties.retry().delayMs())
                .deadLetterExchange("")
                .deadLetterRoutingKey(queues.notificacionesCliente())
                .build();
    }

    @Bean
    public Queue notificacionesClienteDlq() {
        return QueueBuilder.durable(properties.queues().notificacionesClienteDlq())
                .ttl((int) properties.dlq().retentionMs())
                .maxLength(properties.dlq().maxLength())
                .build();
    }

    /** pedidos.events --pedido.estado.actualizado--> pedidos.notificaciones-cliente.queue */
    @Bean
    public Binding notificacionesClienteBinding() {
        return BindingBuilder.bind(notificacionesClienteQueue())
                .to(pedidosExchange())
                .with(properties.routingKeys().pedidoEstadoActualizado());
    }

    /** pedidos.dlx --pedidos.notificaciones-cliente.dlq--> pedidos.notificaciones-cliente.dlq */
    @Bean
    public Binding notificacionesClienteDlqBinding() {
        return BindingBuilder.bind(notificacionesClienteDlq())
                .to(pedidosDeadLetterExchange())
                .with(properties.queues().notificacionesClienteDlq());
    }
}
