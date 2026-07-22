package com.jwpaisley.models;

import java.util.UUID;

public record PhotoCollection(
    UUID id,
    String title,
    String caption,

    String createdAt,
    String updatedAt
) {}