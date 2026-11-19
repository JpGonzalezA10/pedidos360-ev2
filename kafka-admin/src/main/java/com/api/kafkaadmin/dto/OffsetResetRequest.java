package com.api.kafkaadmin.dto;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/*
 * Reset de offsets de un consumer group sobre un topico (equivalente a
 * kafka-consumer-groups --reset-offsets). Requiere que el grupo no tenga consumidores activos.
 *  - EARLIEST: reprocesar todo lo retenido (replay).
 *  - LATEST: saltar el backlog (lag = 0).
 *  - TO_OFFSET: posicionar en un offset concreto (opcionalmente solo en una particion).
 */
public record OffsetResetRequest(
        @NotBlank String topic,
        @NotNull Strategy strategy,
        @Min(0) Long offset,
        @Min(0) Integer partition) {

    public enum Strategy {
        EARLIEST,
        LATEST,
        TO_OFFSET
    }

    @AssertTrue(message = "TO_OFFSET requiere 'offset'")
    public boolean isOffsetPresentWhenRequired() {
        return strategy != Strategy.TO_OFFSET || offset != null;
    }
}
