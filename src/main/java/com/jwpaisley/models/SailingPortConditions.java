package com.jwpaisley.models;

public record SailingPortConditions(
    Double windSpeed,
    String windDirection,
    Double gustSpeed,
    Double waveSize,
    Double currentSpeed,
    Double waterTemperature,
    String currentTide,
    Double tideSizeFeet,
    Double airTemperature,
    WeatherType weather,
    String marineAlert,
    String fetchedAt
) {}
