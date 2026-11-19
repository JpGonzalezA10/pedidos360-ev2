package com.api.kafkaadmin.controller;

import com.api.kafkaadmin.dto.AdminResponses.DeadLetterMessage;
import com.api.kafkaadmin.dto.AdminResponses.DeadLetterSummary;
import com.api.kafkaadmin.dto.AdminResponses.ReprocessResult;
import com.api.kafkaadmin.service.DeadLetterService;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * Dead Letter Topics: resumen con alerta, inspeccion de mensajes fallidos y reprocesamiento.
 */
@Validated
@RestController
@RequestMapping("/api/kafka/dead-letters")
public class DeadLetterController {

    private final DeadLetterService deadLetterService;

    public DeadLetterController(DeadLetterService deadLetterService) {
        this.deadLetterService = deadLetterService;
    }

    @GetMapping
    public DeadLetterSummary summary() {
        return deadLetterService.summary();
    }

    @GetMapping("/{topic}/messages")
    public List<DeadLetterMessage> messages(
            @PathVariable String topic,
            @RequestParam(defaultValue = "10") @Min(1) @Max(100) int limit) {
        return deadLetterService.peek(topic, limit);
    }

    @PostMapping("/{topic}/reprocess")
    public ReprocessResult reprocess(
            @PathVariable String topic,
            @RequestParam(required = false) @Min(1) @Max(10_000) Integer maxMessages,
            @RequestParam(required = false) String targetTopic) {
        return deadLetterService.reprocess(topic, maxMessages, targetTopic);
    }
}
