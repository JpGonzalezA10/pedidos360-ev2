package com.api.kafkaadmin.controller;

import com.api.kafkaadmin.dto.AdminResponses.ConsumerGroupDetail;
import com.api.kafkaadmin.dto.AdminResponses.ConsumerGroupSummary;
import com.api.kafkaadmin.dto.AdminResponses.OperationResult;
import com.api.kafkaadmin.dto.OffsetResetRequest;
import com.api.kafkaadmin.service.KafkaAdminService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * Consumer groups: listado, detalle con miembros, asignacion de particiones y lag,
 * reset de offsets (replay o saltar backlog) y eliminacion.
 */
@RestController
@RequestMapping("/api/kafka/consumer-groups")
public class ConsumerGroupController {

    private final KafkaAdminService kafkaAdminService;

    public ConsumerGroupController(KafkaAdminService kafkaAdminService) {
        this.kafkaAdminService = kafkaAdminService;
    }

    @GetMapping
    public List<ConsumerGroupSummary> list() {
        return kafkaAdminService.listConsumerGroups();
    }

    @GetMapping("/{groupId}")
    public ConsumerGroupDetail describe(@PathVariable String groupId) {
        return kafkaAdminService.describeConsumerGroup(groupId);
    }

    @PostMapping("/{groupId}/reset-offsets")
    public ConsumerGroupDetail resetOffsets(@PathVariable String groupId, @Valid @RequestBody OffsetResetRequest request) {
        return kafkaAdminService.resetOffsets(groupId, request);
    }

    @DeleteMapping("/{groupId}")
    public OperationResult delete(@PathVariable String groupId) {
        return kafkaAdminService.deleteConsumerGroup(groupId);
    }
}
