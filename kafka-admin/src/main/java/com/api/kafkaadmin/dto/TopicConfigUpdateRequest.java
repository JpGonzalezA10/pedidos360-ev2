package com.api.kafkaadmin.dto;

import jakarta.validation.constraints.AssertTrue;
import java.util.List;
import java.util.Map;

/*
 * Modificacion incremental de la configuracion de un topico (IncrementalAlterConfigs):
 *  - set: claves a definir o cambiar (ej. retention.ms=86400000).
 *  - delete: claves a eliminar del topico (vuelven al valor por defecto del broker).
 */
public record TopicConfigUpdateRequest(
        Map<String, String> set,
        List<String> delete) {

    public Map<String, String> safeSet() {
        return set == null ? Map.of() : set;
    }

    public List<String> safeDelete() {
        return delete == null ? List.of() : delete;
    }

    @AssertTrue(message = "debe indicar al menos una clave en 'set' o 'delete'")
    public boolean isNotEmpty() {
        return !safeSet().isEmpty() || !safeDelete().isEmpty();
    }
}
