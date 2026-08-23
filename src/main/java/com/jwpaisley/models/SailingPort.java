package com.jwpaisley.models;

import java.math.BigDecimal;
import java.util.UUID;

public record SailingPort(
    UUID id,
    String name,
    BigDecimal latitude,
    BigDecimal longitude,
    String createdAt,
    String updatedAt
) {}
