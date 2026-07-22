package com.jwpaisley.models;

import java.util.List;
import java.util.UUID;

public record Recipe(
    UUID id,
    String name,
    String description,
    String emoji,

    int servings,
    int calories,
    int protein,
    int fat,
    int carbohydrates,
    int sugar,
    int fiber,
    int sodium,

    List<String> ingredients,
    List<String> miseEnPlaceSteps,
    List<String> instructions,

    String createdAt,
    String updatedAt
) {}