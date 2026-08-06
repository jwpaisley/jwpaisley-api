package com.jwpaisley.models;

import java.math.BigDecimal;
import java.util.UUID;

public record SailingPortWithConditions(
    UUID id,
    String name,
    BigDecimal latitude,
    BigDecimal longitude,
    String tideStationId,
    String currentStationId,
    String buoyStationId,
    String nwsOffice,
    Integer nwsGridX,
    Integer nwsGridY,
    String createdAt,
    String updatedAt,
    SailingPortConditions conditions
) {
    public static SailingPortWithConditions from(SailingPort port, SailingPortConditions conditions) {
        return new SailingPortWithConditions(
            port.id(),
            port.name(),
            port.latitude(),
            port.longitude(),
            port.tideStationId(),
            port.currentStationId(),
            port.buoyStationId(),
            port.nwsOffice(),
            port.nwsGridX(),
            port.nwsGridY(),
            port.createdAt(),
            port.updatedAt(),
            conditions
        );
    }
}
