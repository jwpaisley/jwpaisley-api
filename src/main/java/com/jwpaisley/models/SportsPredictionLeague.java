package com.jwpaisley.models;

import java.util.UUID;

public record SportsPredictionLeague(
    UUID id,
    String name,
    String imageUrl,
    String description,
    Sport sport,
    boolean isPrivate,
    
    int firstPlacePrizeCoins,
    int secondPlacePrizeCoins,
    int thirdPlacePrizeCoins,

    int apiSportsLeagueId,
    int apiSportsSeasonId,
    String leagueImageUrl,
    String leagueStartDate,
    String leagueEndDate,

    String createdAt,
    String updatedAt
) {}