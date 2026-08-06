package com.jwpaisley.models;

import java.math.BigDecimal;
import java.util.UUID;

public record SailingPort(
    UUID id,
    String name,
    BigDecimal latitude,
    BigDecimal longitude,
    String tideStationId,
    String currentStationId,
    String buoyStationId,
    String observationStationId,
    String nwsOffice,
    Integer nwsGridX,
    Integer nwsGridY,
    String createdAt,
    String updatedAt
) {}
