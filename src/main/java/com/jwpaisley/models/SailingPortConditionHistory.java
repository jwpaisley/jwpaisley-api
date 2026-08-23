package com.jwpaisley.models;

import java.util.UUID;

public record SailingPortConditionHistory(
    UUID id,
    UUID sailingPortId,
    Double windSpeed,
    Double windDirection,
    Double gustSpeed,
    Double currentSpeed,
    Double currentDirection,
    Double waveHeight,
    Double wavePeriod,
    Double waterTemperature,
    Double airTemperature,
    Double cloudCover,
    Double precipitation,
    Double visibility,
    String weather,
    String forecastTime,
    String fetchedAt,
    String rawResponse
) {}