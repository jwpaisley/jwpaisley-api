package com.jwpaisley.models;

import java.math.BigDecimal;
import java.util.UUID;

public record SailingPortWithConditions(
    UUID id,
    String name,
    BigDecimal latitude,
    BigDecimal longitude,
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
            port.createdAt(),
            port.updatedAt(),
            conditions
        );
    }
}
