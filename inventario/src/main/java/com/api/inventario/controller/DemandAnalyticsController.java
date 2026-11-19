package com.api.inventario.controller;

import com.api.inventario.service.DemandAnalyticsService;
import com.api.inventario.service.InventoryService;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * EP5 - Endpoints del stream de inventario:
 *  - GET  /api/stream/demand: ranking de demanda por SKU calculado desde el stream de pedidos.
 *  - POST /api/stream/stock/republish: publica la foto de stock de todos los productos en el topico compactado.
 */
@RestController
@RequestMapping("/api/stream")
public class DemandAnalyticsController {

    private final DemandAnalyticsService demandAnalyticsService;
    private final InventoryService inventoryService;

    public DemandAnalyticsController(DemandAnalyticsService demandAnalyticsService, InventoryService inventoryService) {
        this.demandAnalyticsService = demandAnalyticsService;
        this.inventoryService = inventoryService;
    }

    @GetMapping("/demand")
    public List<DemandAnalyticsService.DemandStats> ranking() {
        return demandAnalyticsService.ranking();
    }

    @PostMapping("/stock/republish")
    public Map<String, Integer> republishStock() {
        return Map.of("snapshotsPublicados", inventoryService.republishStockSnapshots());
    }
}
