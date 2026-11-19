package com.api.pedidos.streaming.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.config.TopicBuilder;

/*
 * EP5 - Declaracion de topicos como codigo (KafkaAdmin los crea al iniciar si no existen
 * y, con spring.kafka.admin.modify-topic-configs=true, alinea su configuracion).
 *
 * pedidos es DUENO del topico pedidos360.pedidos.eventos (lo produce) y declara la DLT
 * del topico que consume (pedidos360.inventario.stock.DLT).
 */
@Configuration
@EnableConfigurationProperties(StreamingProperties.class)
public class KafkaTopicConfig {

    @Bean
    public KafkaAdmin.NewTopics pedidosTopics(StreamingProperties properties) {
        StreamingProperties.TopicSpec eventos = properties.topics().pedidosEventos();
        StreamingProperties.TopicSpec stock = properties.topics().inventarioStock();
        StreamingProperties.DeadLetter dlt = properties.deadLetter();
        return new KafkaAdmin.NewTopics(
                build(eventos),
                // La DLT replica las particiones del topico origen: el recoverer conserva la particion.
                TopicBuilder.name(dlt.topicFor(stock.name()))
                        .partitions(stock.partitions())
                        .replicas(dlt.replicas())
                        .configs(dlt.configs())
                        .build());
    }

    private NewTopic build(StreamingProperties.TopicSpec spec) {
        return TopicBuilder.name(spec.name())
                .partitions(spec.partitions())
                .replicas(spec.replicas())
                .configs(spec.configs())
                .build();
    }
}
