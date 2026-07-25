package com.jwpaisley.models;

import java.util.UUID;

public record User(
    UUID id,
    String firstName,
    String lastName,
    String emailAddress,
    String profilePictureUrl,
    String lastLogin,
    String createdAt,
    String updatedAt
) {}
