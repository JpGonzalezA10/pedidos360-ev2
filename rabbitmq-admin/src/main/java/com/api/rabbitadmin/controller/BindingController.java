package com.api.rabbitadmin.controller;

import com.api.rabbitadmin.dto.AdminResponses.BindingResponse;
import com.api.rabbitadmin.dto.AdminResponses.OperationResponse;
import com.api.rabbitadmin.dto.BindingRequest;
import com.api.rabbitadmin.service.RabbitAdminService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * Recurso REST: bindings exchange -> cola.
 *  GET    /api/rabbit/bindings          lista bindings
 *  POST   /api/rabbit/bindings          crea un binding (201)
 *  DELETE /api/rabbit/bindings?exchange=&queue=&routingKey=   elimina un binding
 *
 * Un binding no tiene identificador propio: se identifica por exchange + cola + routing key,
 * enviados como query params y validados con el mismo DTO (BindingRequest).
 */
@RestController
@RequestMapping("/api/rabbit/bindings")
public class BindingController {

    private final RabbitAdminService adminService;

    public BindingController(RabbitAdminService adminService) {
        this.adminService = adminService;
    }

    @GetMapping
    public List<BindingResponse> list() {
        return adminService.listBindings();
    }

    @PostMapping
    public ResponseEntity<BindingResponse> create(@Valid @RequestBody BindingRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(adminService.createBinding(request));
    }

    @DeleteMapping
    public OperationResponse delete(@Valid @ModelAttribute BindingRequest request) {
        return adminService.deleteBinding(request);
    }
}
