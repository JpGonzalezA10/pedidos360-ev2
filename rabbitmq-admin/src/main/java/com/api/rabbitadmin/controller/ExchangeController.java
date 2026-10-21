package com.api.rabbitadmin.controller;

import com.api.rabbitadmin.dto.AdminResponses.ExchangeResponse;
import com.api.rabbitadmin.dto.AdminResponses.OperationResponse;
import com.api.rabbitadmin.dto.ExchangeCreateRequest;
import com.api.rabbitadmin.dto.ValidationPatterns;
import com.api.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.net.URI;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * Recurso REST: exchanges de RabbitMQ.
 *  GET    /api/rabbit/exchanges          lista exchanges
 *  GET    /api/rabbit/exchanges/{name}   detalle de un exchange
 *  POST   /api/rabbit/exchanges          crea un exchange direct/topic/fanout/headers (201 + Location)
 *  DELETE /api/rabbit/exchanges/{name}   elimina un exchange
 */
@Validated
@RestController
@RequestMapping("/api/rabbit/exchanges")
public class ExchangeController {

    private final RabbitAdminService adminService;

    public ExchangeController(RabbitAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public List<ExchangeResponse> list() {
        return adminService.listExchanges();
    }

    @GetMapping("/{name}")
    public ExchangeResponse get(@PathVariable @Pattern(regexp = ValidationPatterns.RESOURCE_NAME,
            message = ValidationPatterns.RESOURCE_NAME_MESSAGE) String name) {
        return adminService.getExchange(name);
    }

    @PostMapping
    public ResponseEntity<ExchangeResponse> create(@Valid @RequestBody ExchangeCreateRequest request) {
        ExchangeResponse created = adminService.createExchange(request);
        return ResponseEntity.created(URI.create("/api/rabbit/exchanges/" + created.name())).body(created);
    }

    @DeleteMapping("/{name}")
    public OperationResponse delete(@PathVariable @Pattern(regexp = ValidationPatterns.RESOURCE_NAME,
            message = ValidationPatterns.RESOURCE_NAME_MESSAGE) String name) {
        return adminService.deleteExchange(name);
    }
}
