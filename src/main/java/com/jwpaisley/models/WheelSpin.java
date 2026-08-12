package com.jwpaisley.models;

import java.util.UUID;

public record WheelSpin(
    UUID id,
    UUID userId,
    int outcome,
    String createdAt,
    String updatedAt
) {}
