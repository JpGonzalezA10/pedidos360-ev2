package com.api.rabbitadmin.controller;

import com.api.rabbitadmin.dto.AdminResponses.OperationResponse;
import com.api.rabbitadmin.dto.AdminResponses.QueueResponse;
import com.api.rabbitadmin.dto.AdminResponses.QueuedMessageResponse;
import com.api.rabbitadmin.dto.QueueCreateRequest;
import com.api.rabbitadmin.dto.ValidationPatterns;
import com.api.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/*
 * Recurso REST: colas de RabbitMQ.
 *  GET    /api/rabbit/queues                    lista colas con mensajes y consumidores
 *  GET    /api/rabbit/queues/{name}             detalle de una cola
 *  POST   /api/rabbit/queues                    crea una cola (201 + Location)
 *  DELETE /api/rabbit/queues/{name}             elimina una cola (?ifUnused=true&ifEmpty=true)
 *  GET    /api/rabbit/queues/{name}/messages    inspecciona mensajes sin consumirlos
 *  DELETE /api/rabbit/queues/{name}/messages    purga los mensajes de la cola
 */
@Validated
@RestController
@RequestMapping("/api/rabbit/queues")
public class QueueController {

    private final RabbitAdminService adminService;

    public QueueController(RabbitAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public List<QueueResponse> list() {
        return adminService.listQueues();
    }

    @GetMapping("/{name}")
    public QueueResponse get(@PathVariable @Pattern(regexp = ValidationPatterns.RESOURCE_NAME,
            message = ValidationPatterns.RESOURCE_NAME_MESSAGE) String name) {
        return adminService.getQueue(name);
    }

    @PostMapping
    public ResponseEntity<QueueResponse> create(@Valid @RequestBody QueueCreateRequest request) {
        QueueResponse created = adminService.createQueue(request);
        return ResponseEntity.created(URI.create("/api/rabbit/queues/" + created.name())).body(created);
    }

    @DeleteMapping("/{name}")
    public OperationResponse delete(
            @PathVariable @Pattern(regexp = ValidationPatterns.RESOURCE_NAME,
                    message = ValidationPatterns.RESOURCE_NAME_MESSAGE) String name,
            @RequestParam(defaultValue = "false") boolean ifUnused,
            @RequestParam(defaultValue = "false") boolean ifEmpty) {
        return adminService.deleteQueue(name, ifUnused, ifEmpty);
    }

    @GetMapping("/{name}/messages")
    public List<QueuedMessageResponse> peekMessages(
            @PathVariable @Pattern(regexp = ValidationPatterns.RESOURCE_NAME,
                    message = ValidationPatterns.RESOURCE_NAME_MESSAGE) String name,
            @RequestParam(defaultValue = "10") @Min(value = 1, message = "debe ser al menos 1")
            @Max(value = 50, message = "no puede superar 50") int count) {
        return adminService.peekMessages(name, count);
    }

    @DeleteMapping("/{name}/messages")
    public OperationResponse purge(@PathVariable @Pattern(regexp = ValidationPatterns.RESOURCE_NAME,
            message = ValidationPatterns.RESOURCE_NAME_MESSAGE) String name) {
        return adminService.purgeQueue(name);
    }
}
