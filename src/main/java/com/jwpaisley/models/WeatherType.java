package com.jwpaisley.models;

import com.jwpaisley.helpers.LoggingHelper;

public enum WeatherType {
    SUNNY("Sunny"),
    PARTLY_SUNNY("Partly Sunny"),
    CLOUDY("Cloudy"),
    LIGHT_SHOWERS("Light Showers"),
    THUNDERSTORM("Thunderstorm"),
    SNOWSTORM("Snowstorm"),
    RAINY("Rainy"),
    SNOWY("Snowy"),
    STORMY("Stormy");

    private final String displayName;

    WeatherType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public static WeatherType fromText(String value) {
        if (value == null || value.isBlank()) {
            return CLOUDY;
        }

        String normalized = value.toLowerCase().replaceAll("[^a-z]+", " ").trim();

        // 1. Severe / Storms
        if (normalized.contains("thunder") || normalized.contains("tstorm") || normalized.contains("squall")) {
            return THUNDERSTORM;
        }
        if (normalized.contains("storm")) {
            return normalized.contains("snow") || normalized.contains("blizzard") ? SNOWSTORM : STORMY;
        }

        // 2. Snow
        if (normalized.contains("snow") || normalized.contains("flurry") || normalized.contains("sleet") || normalized.contains("ice")) {
            return SNOWY;
        }

        // 3. Rain & Showers
        if (normalized.contains("shower") || normalized.contains("drizzle") || normalized.contains("rain") || normalized.contains("sprinkle")) {
            return (normalized.contains("light") || normalized.contains("slight") || normalized.contains("chance")) 
                   ? LIGHT_SHOWERS : RAINY;
        }

        // 4. Partly Sunny / Partly Cloudy
        if (normalized.contains("partly") || normalized.contains("few clouds") || normalized.contains("scattered")) {
            return PARTLY_SUNNY;
        }

        // 5. Sunny / Clear / Fair
        if (normalized.contains("sun") || normalized.contains("clear") || normalized.contains("fair")) {
            return SUNNY;
        }

        // 6. Overcast / Fog / Haze / Clouds
        if (normalized.contains("cloud") || normalized.contains("overcast") || normalized.contains("fog") || normalized.contains("haze")) {
            return CLOUDY;
        }

        LoggingHelper.debug("unmatched shortforecast text '" + value.toLowerCase() + "', defaulting to cloudy.");
        return CLOUDY;
    }
}