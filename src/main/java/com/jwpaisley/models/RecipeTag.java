package com.jwpaisley.models;

import java.util.UUID;

public record RecipeTag(
    UUID id,
    String name,
    String description,
    String createdAt,
    String updatedAt
) {}
