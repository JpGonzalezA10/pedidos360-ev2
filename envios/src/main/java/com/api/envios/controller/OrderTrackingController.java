package com.api.envios.controller;

import com.api.envios.service.OrderTrackingService;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/*
 * EP5 - Tablero de seguimiento construido desde el stream de pedidos (evidencia del consumidor).
 */
@RestController
@RequestMapping("/api/stream/orders")
public class OrderTrackingController {

    private final OrderTrackingService orderTrackingService;

    public OrderTrackingController(OrderTrackingService orderTrackingService) {
        this.orderTrackingService = orderTrackingService;
    }

    @GetMapping("/dashboard")
    public Map<String, Object> dashboard() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pedidosPorEstado", orderTrackingService.countByCurrentStatus());
        body.put("pendientesDeDespacho", orderTrackingService.pendingDispatch());
        return body;
    }

    @GetMapping("/{orderId}/timeline")
    public OrderTrackingService.OrderTimeline timeline(@PathVariable UUID orderId) {
        return orderTrackingService.findTimeline(orderId);
    }
}
