package com.jwpaisley.models;

import java.math.BigDecimal;
import java.util.UUID;

public record WheelOption(
    UUID id,
    int value,
    BigDecimal probability,
    String createdAt,
    String updatedAt
) {}
