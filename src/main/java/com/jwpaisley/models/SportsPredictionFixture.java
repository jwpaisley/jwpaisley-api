package com.jwpaisley.models;

import java.util.UUID;

public record SportsPredictionFixture(
    UUID id,
    int apiSportsFixtureId,
    int apiSportsLeagueId,
    int apiSportsSeasonId,
    UUID homeTeamId,
    UUID awayTeamId,
    String commenceTime,
    Double homeOdds,
    Double awayOdds,
    Double drawOdds,
    String status,
    Integer homeScore,
    Integer awayScore,
    UUID winningTeamId,
    String createdAt,
    String updatedAt
) {}