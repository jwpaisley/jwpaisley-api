package com.jwpaisley.models;

import java.util.UUID;

public record SportsPredictionTeam(
    UUID id,
    int apiSportsTeamId,
    String name,
    String code,
    String logoUrl,
    String createdAt,
    String updatedAt
) {}