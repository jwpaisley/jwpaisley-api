package com.jwpaisley.models;

import java.util.UUID;

public record SailboatPhoto(
    UUID id,
    UUID sailboatId,
    UUID voyageId,
    String photoUrl,
    boolean showInCarousel,
    String caption,
    String createdAt,
    String updatedAt
) {}
