package com.jwpaisley.models;

import java.util.UUID;
import java.util.List;

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
    List<String> instructions
) {}