package com.api.rabbitadmin.controller;

import com.api.rabbitadmin.dto.AdminResponses.DeadLetterSummaryResponse;
import com.api.rabbitadmin.service.RabbitAdminService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * Recurso REST: monitoreo de Dead Letter Queues.
 *  GET /api/rabbit/dead-letters   total de mensajes en DLQ, bandera de alerta y detalle por cola.
 * El frontend lo consulta periodicamente para mostrar la alerta visual.
 */
@RestController
@RequestMapping("/api/rabbit/dead-letters")
public class DeadLetterController {

    private final RabbitAdminService adminService;

    public DeadLetterController(RabbitAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public DeadLetterSummaryResponse summary() {
        return adminService.deadLetterSummary();
    }
}
