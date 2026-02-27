package com.jwpaisley.models;

import java.util.List;

public record Recipe(
    int id,
    String name,
    String description,
    String emoji,

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