package com.jwpaisley.models;

import java.util.UUID;

public record Photo(
    UUID id,
    UUID collection,
    String image,
    String caption,
    String location,
    String takenDate,

    String createdAt,
    String updatedAt
) {}