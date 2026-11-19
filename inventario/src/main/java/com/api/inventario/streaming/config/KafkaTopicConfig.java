package com.api.inventario.streaming.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/*
 * EP5 - Topicos declarados por inventario.
 *  - pedidos360.inventario.stock (dueno): cleanup.policy=compact -> Kafka conserva solo el ultimo
 *    snapshot de cada SKU (key). Es una "tabla" de stock que cualquier servicio puede reconstruir.
 *  - pedidos360.pedidos.eventos.DLT: DLT del topico que inventario consume.
 */
@Configuration
@EnableConfigurationProperties(StreamingProperties.class)
public class KafkaTopicConfig {

    @Bean
    public KafkaAdmin.NewTopics inventarioTopics(StreamingProperties properties) {
        StreamingProperties.TopicSpec stock = properties.topics().inventarioStock();
        StreamingProperties.TopicSpec pedidos = properties.topics().pedidosEventos();
        StreamingProperties.DeadLetter dlt = properties.deadLetter();
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(stock.name())
                        .partitions(stock.partitions())
                        .replicas(stock.replicas())
                        .configs(stock.configs())
                        .build(),
                TopicBuilder.name(dlt.topicFor(pedidos.name()))
                        .partitions(pedidos.partitions())
                        .replicas(dlt.replicas())
                        .configs(dlt.configs())
                        .build());
    }
}
