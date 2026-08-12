package com.jwpaisley.models;

import java.util.UUID;

public record SportsPredictionPick(
    UUID id,
    UUID leagueId,
    UUID userId,
    UUID fixtureId,
    UUID selectedTeamId,
    boolean isDrawPick,
    double payoutMultiplier,
    String status,
    int coinsAwarded,
    int pointsAwarded,
    String createdAt,
    String updatedAt
) {}