package com.api.pedidos.controller;

import com.api.pedidos.model.StockSnapshot;
import com.api.pedidos.service.StockProjectionService;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * EP5 - Consulta de la proyeccion de stock construida desde Kafka (evidencia del consumidor).
 * Protegido por la API key interna (ApiKeyAuthFilter cubre /api/**).
 */
@RestController
@RequestMapping("/api/stream/stock")
public class StockProjectionController {

    private final StockProjectionService stockProjectionService;

    public StockProjectionController(StockProjectionService stockProjectionService) {
        this.stockProjectionService = stockProjectionService;
    }

    @GetMapping
    public List<StockSnapshot> findAll() {
        return stockProjectionService.findAll();
    }

    @GetMapping("/{sku}")
    public StockSnapshot findBySku(@PathVariable String sku) {
        return stockProjectionService.findBySku(sku);
    }
}
