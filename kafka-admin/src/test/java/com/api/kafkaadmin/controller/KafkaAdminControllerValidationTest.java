package com.api.kafkaadmin.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.api.kafkaadmin.service.KafkaAdminService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/*
 * EP5 - Validacion de entrada de la API del administrador (sin broker): los datos invalidos
 * se rechazan con 400 y detalle por campo antes de llegar a Kafka.
 */
@WebMvcTest({TopicController.class, ConsumerGroupController.class})
class KafkaAdminControllerValidationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private KafkaAdminService kafkaAdminService;

    @Test
    void createTopicRejectsInvalidNameAndPartitions() throws Exception {
        mockMvc.perform(post("/api/kafka/topics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"topico invalido!\",\"partitions\":0,\"replicationFactor\":1}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.name").exists())
                .andExpect(jsonPath("$.details.partitions").exists());
    }

    @Test
    void updateConfigsRequiresAtLeastOneChange() throws Exception {
        mockMvc.perform(patch("/api/kafka/topics/pedidos360.pedidos.eventos/configs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void resetOffsetsToOffsetRequiresOffset() throws Exception {
        mockMvc.perform(post("/api/kafka/consumer-groups/inventario-demanda/reset-offsets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"topic\":\"pedidos360.pedidos.eventos\",\"strategy\":\"TO_OFFSET\"}"))
                .andExpect(status().isBadRequest());
    }
}
