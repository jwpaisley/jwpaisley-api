package com.jwpaisley.models;

import java.util.UUID;

public record SportsPredictionLeagueParticipant(
    UUID id,
    UUID leagueId,
    UUID userId,
    int points,
    String joinedAt
) {}