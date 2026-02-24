package com.jwpaisley.models;

import java.time.OffsetDateTime;

public record Ingredient(
    int id,
    String name,
    String emoji,
    double calories,
    double carbohydrates,
    double protein,
    double fat,
    double fiber,
    double sodium,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {}