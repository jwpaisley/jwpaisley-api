package com.jwpaisley.helpers;

import com.jwpaisley.models.Sport;
import java.net.URI;
import java.net.http.HttpRequest;
import java.util.Map;

public class ApiSportsHelper {
    private static final String API_SPORTS_API_KEY = System.getenv().getOrDefault("API_SPORTS_API_KEY", "");

    private static final Map<Sport, String> SPORT_ENDPOINTS = Map.of(
        Sport.SOCCER, "https://v3.football.api-sports.io",
        Sport.FOOTBALL, "https://v3.american-football.api-sports.io",
        Sport.HOCKEY, "https://v3.hockey.api-sports.io",
        Sport.BASEBALL, "https://v3.baseball.api-sports.io",
        Sport.BASKETBALL, "https://v3.basketball.api-sports.io",
        Sport.F1, "https://v3.formula-1.api-sports.io"
    );

    public static String getApiKey() {
        return API_SPORTS_API_KEY;
    }

    public static String getBaseUrlForSport(Sport sport) {
        if (sport == null) {
            throw new IllegalArgumentException("Sport cannot be null");
        }

        String endpoint = SPORT_ENDPOINTS.get(sport);
        if (endpoint == null) {
            throw new IllegalArgumentException("Unsupported sport for API Sports: " + sport);
        }

        return endpoint;
    }

    public static HttpRequest.Builder buildRequestForSport(Sport sport, String path) {
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("API Sports path is required");
        }

        String normalizedPath = path.startsWith("/") ? path : "/" + path;
        return buildRequest(getBaseUrlForSport(sport) + normalizedPath);
    }

    public static HttpRequest.Builder buildRequest(String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(URI.create(url))
            .header("Accept", "application/json");

        if (!API_SPORTS_API_KEY.isBlank()) {
            builder.header("x-apisports-key", API_SPORTS_API_KEY);
        }

        return builder;
    }
}
