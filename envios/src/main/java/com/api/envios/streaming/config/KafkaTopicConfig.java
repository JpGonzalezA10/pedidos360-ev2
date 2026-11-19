package com.api.envios.streaming.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;

/*
 * EP5 - envios no es dueno de ningun topico de negocio; solo declara la DLT del topico que consume
 * (pedidos360.pedidos.eventos.DLT, compartida con inventario: misma definicion en ambos servicios).
 */
@Configuration
@EnableConfigurationProperties(StreamingProperties.class)
public class KafkaTopicConfig {

    @Bean
    public KafkaAdmin.NewTopics enviosTopics(StreamingProperties properties) {
        StreamingProperties.TopicSpec pedidos = properties.topics().pedidosEventos();
        StreamingProperties.DeadLetter dlt = properties.deadLetter();
        return new KafkaAdmin.NewTopics(
                TopicBuilder.name(dlt.topicFor(pedidos.name()))
                        .partitions(pedidos.partitions())
                        .replicas(dlt.replicas())
                        .configs(dlt.configs())
                        .build());
    }
}
