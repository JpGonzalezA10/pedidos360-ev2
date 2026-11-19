package com.api.kafkaadmin.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/*
 * Aumento de particiones de un topico. Kafka solo permite AUMENTAR (nunca reducir) y el nuevo
 * total debe ser mayor al actual. Ojo: cambia el mapeo key -> particion para mensajes futuros.
 */
public record PartitionIncreaseRequest(@Min(2) @Max(1000) int totalPartitions) {
}
