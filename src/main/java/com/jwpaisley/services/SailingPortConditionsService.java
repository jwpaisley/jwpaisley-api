package com.jwpaisley.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jwpaisley.helpers.LoggingHelper;
import com.jwpaisley.models.SailingPort;
import com.jwpaisley.models.SailingPortConditions;
import com.jwpaisley.models.WeatherType;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.sql.DataSource;

public class SailingPortConditionsService {
    private static final SailingPortConditionsService INSTANCE = new SailingPortConditionsService();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String STORMGLASS_URL = "https://api.stormglass.io/v2/weather/point";
    private static final String STORMGLASS_SOURCE = "sg";
    private static final String[] STORMGLASS_PARAMS = {
        "windSpeed",
        "windDirection",
        "gust",
        "currentSpeed",
        "currentDirection",
        "waveHeight",
        "wavePeriod",
        "waterTemperature",
        "airTemperature",
        "cloudCover",
        "precipitation",
        "visibility"
    };

    private record FetchResult(SailingPortConditions conditions, String rawPayload) {}

    private SailingPortConditionsService() {}

    public static SailingPortConditionsService getInstance() {
        return INSTANCE;
    }

    public SailingPortConditions getConditions(SailingPort port) {
        if (port == null) {
            return null;
        }

        SailingPortConditions conditions = loadLatestConditionsFromHistory(port.id());
        if (conditions == null) {
            LoggingHelper.warning("No saved sailing conditions found for port " + port.name() + " (" + port.id() + ")");
        }
        return conditions;
    }

    public SailingPortConditions refreshConditions(SailingPort port) {
        if (port == null) {
            return null;
        }

        LoggingHelper.info("Refreshing sailing conditions from Stormglass for port " + port.name() + " (" + port.id() + ")");
        return fetchAndPersistConditions(port);
    }

    private SailingPortConditions loadLatestConditionsFromHistory(java.util.UUID portId) {
        String sql = """
            SELECT *
            FROM public.sailing_port_condition_history
            WHERE sailing_port_id = ?::uuid
            ORDER BY fetched_at DESC
            LIMIT 1
        """;

        DataSource ds = DatabaseService.getInstance().getDataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, portId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return mapConditionsFromResultSet(rs);
                }
            }
            LoggingHelper.debug("No history row found in sailing_port_condition_history for portId=" + portId);
        } catch (SQLException e) {
            LoggingHelper.error("Unable to load sailing port history: " + e.getMessage());
        }

        return null;
    }

    private SailingPortConditions fetchAndPersistConditions(SailingPort port) {
        FetchResult result = fetchConditions(port);
        if (result == null || result.conditions() == null) {
            return null;
        }

        persistHistorySnapshot(port, result);
        return result.conditions();
    }

    private FetchResult fetchConditions(SailingPort port) {
        try {
            JsonNode root = fetchStormglassPayload(port);
            JsonNode hours = root.path("hours");
            if (!hours.isArray() || hours.isEmpty()) {
                throw new IllegalStateException("stormglass returned no hourly conditions");
            }

            JsonNode currentHour = hours.get(0);
            Double windSpeed = metersPerSecondToKnots(extractModelValue(currentHour, "windSpeed"));
            Double windDirection = normalizeDegrees(extractModelValue(currentHour, "windDirection"));
            Double gustSpeed = metersPerSecondToKnots(extractModelValue(currentHour, "gust"));
            Double currentSpeed = metersPerSecondToKnots(extractModelValue(currentHour, "currentSpeed"));
            Double currentDirection = normalizeDegrees(extractModelValue(currentHour, "currentDirection"));
            Double waveHeight = metersToFeet(extractModelValue(currentHour, "waveHeight"));
            Double wavePeriod = roundToOneDecimal(extractModelValue(currentHour, "wavePeriod"));
            Double waterTemperature = celsiusToFahrenheit(extractModelValue(currentHour, "waterTemperature"));
            Double airTemperature = celsiusToFahrenheit(extractModelValue(currentHour, "airTemperature"));
            Double cloudCover = roundToOneDecimal(extractModelValue(currentHour, "cloudCover"));
            Double precipitation = roundToOneDecimal(extractModelValue(currentHour, "precipitation"));
            Double visibility = roundToOneDecimal(extractModelValue(currentHour, "visibility"));
            WeatherType weather = mapWeather(cloudCover, precipitation);
            String forecastTime = currentHour.path("time").asText(null);

            SailingPortConditions conditions = new SailingPortConditions(
                windSpeed,
                windDirection,
                gustSpeed,
                currentSpeed,
                currentDirection,
                waveHeight,
                wavePeriod,
                waterTemperature,
                airTemperature,
                cloudCover,
                precipitation,
                visibility,
                weather,
                forecastTime,
                Instant.now().toString()
            );
            LoggingHelper.debug(
                "Stormglass fetch success for port " + port.name()
                    + " (" + port.id() + ")"
                    + ", windKts=" + windSpeed
                    + ", gustKts=" + gustSpeed
                    + ", waveFt=" + waveHeight
                    + ", forecastTime=" + forecastTime
            );
            return new FetchResult(conditions, root.toString());
        } catch (Exception e) {
            LoggingHelper.error("Failed to fetch Stormglass conditions for port " + port.name() + " (" + port.id() + "): " + e.getMessage());
            return null;
        }
    }

    private JsonNode fetchStormglassPayload(SailingPort port) throws Exception {
        String apiKey = System.getenv("STORMGLASSIO_API_KEY");
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("missing STORMGLASSIO_API_KEY env var");
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("lat", port.latitude().toPlainString());
        params.put("lng", port.longitude().toPlainString());
        params.put("params", String.join(",", STORMGLASS_PARAMS));

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", apiKey);
        headers.put("User-Agent", "jwpaisley/1.0");

        return getJson(STORMGLASS_URL, params, headers);
    }

    private void persistHistorySnapshot(SailingPort port, FetchResult result) {
        if (result == null || result.conditions() == null || result.rawPayload() == null || result.rawPayload().isBlank()) {
            LoggingHelper.warning("Skipping sailing conditions history insert due to missing payload for port " + (port != null ? port.name() : "unknown"));
            return;
        }

        String sql = """
            INSERT INTO public.sailing_port_condition_history (
                sailing_port_id, wind_speed, wind_direction, gust_speed, current_speed,
                current_direction, wave_height, wave_period, water_temperature, air_temperature,
                cloud_cover, precipitation, visibility, weather, forecast_time, fetched_at, raw_response
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::timestamptz, ?::jsonb)
        """;

        DataSource ds = DatabaseService.getInstance().getDataSource();
        SailingPortConditions conditions = result.conditions();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, port.id());
            pstmt.setObject(2, conditions.windSpeed());
            pstmt.setObject(3, conditions.windDirection());
            pstmt.setObject(4, conditions.gustSpeed());
            pstmt.setObject(5, conditions.currentSpeed());
            pstmt.setObject(6, conditions.currentDirection());
            pstmt.setObject(7, conditions.waveHeight());
            pstmt.setObject(8, conditions.wavePeriod());
            pstmt.setObject(9, conditions.waterTemperature());
            pstmt.setObject(10, conditions.airTemperature());
            pstmt.setObject(11, conditions.cloudCover());
            pstmt.setObject(12, conditions.precipitation());
            pstmt.setObject(13, conditions.visibility());
            pstmt.setString(14, conditions.weather() != null ? conditions.weather().name() : null);
            pstmt.setTimestamp(15, toSqlTimestamp(conditions.forecastTime()));
            pstmt.setString(16, conditions.fetchedAt());
            pstmt.setString(17, result.rawPayload());
            pstmt.executeUpdate();
            LoggingHelper.info("Inserted sailing conditions history row for port " + port.name() + " (" + port.id() + ") at " + conditions.fetchedAt());
        } catch (SQLException e) {
            LoggingHelper.error(
                "Unable to persist sailing port condition history for port " + port.name() + " (" + port.id() + ")"
                    + ", sqlState=" + e.getSQLState()
                    + ", message=" + e.getMessage()
            );
        }
    }

    private SailingPortConditions mapConditionsFromResultSet(ResultSet rs) throws SQLException {
        String weather = rs.getString("weather");

        return new SailingPortConditions(
            rs.getObject("wind_speed", Double.class),
            rs.getObject("wind_direction", Double.class),
            rs.getObject("gust_speed", Double.class),
            rs.getObject("current_speed", Double.class),
            rs.getObject("current_direction", Double.class),
            rs.getObject("wave_height", Double.class),
            rs.getObject("wave_period", Double.class),
            rs.getObject("water_temperature", Double.class),
            rs.getObject("air_temperature", Double.class),
            rs.getObject("cloud_cover", Double.class),
            rs.getObject("precipitation", Double.class),
            rs.getObject("visibility", Double.class),
            parseWeatherType(weather),
            rs.getTimestamp("forecast_time") != null ? rs.getTimestamp("forecast_time").toInstant().toString() : null,
            rs.getTimestamp("fetched_at") != null ? rs.getTimestamp("fetched_at").toInstant().toString() : null
        );
    }

    private WeatherType parseWeatherType(String weather) {
        if (weather == null || weather.isBlank()) {
            return WeatherType.CLOUDY;
        }

        try {
            return WeatherType.valueOf(weather);
        } catch (IllegalArgumentException e) {
            return WeatherType.fromText(weather);
        }
    }

    private Timestamp toSqlTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Timestamp.from(Instant.parse(value));
        } catch (Exception e) {
            return null;
        }
    }

    private Double extractModelValue(JsonNode hourNode, String fieldName) {
        JsonNode fieldNode = hourNode.path(fieldName);
        if (fieldNode.isMissingNode() || fieldNode.isNull()) {
            return null;
        }

        JsonNode preferredNode = fieldNode.path(STORMGLASS_SOURCE);
        Double preferredValue = parseNumericValue(preferredNode);
        if (preferredValue != null) {
            return preferredValue;
        }

        if (fieldNode.isNumber() || fieldNode.isTextual()) {
            return parseNumericValue(fieldNode);
        }

        if (!fieldNode.isObject()) {
            return null;
        }

        Iterator<Map.Entry<String, JsonNode>> iterator = fieldNode.fields();
        while (iterator.hasNext()) {
            Double candidate = parseNumericValue(iterator.next().getValue());
            if (candidate != null) {
                return candidate;
            }
        }

        return null;
    }

    private Double parseNumericValue(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return null;
        }

        if (node.isNumber()) {
            return node.asDouble();
        }

        if (!node.isTextual()) {
            return null;
        }

        String value = node.asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private WeatherType mapWeather(Double cloudCover, Double precipitation) {
        if (precipitation != null && precipitation > 0.5) {
            return WeatherType.RAINY;
        }

        if (cloudCover == null) {
            return WeatherType.CLOUDY;
        }

        if (cloudCover < 20.0) {
            return WeatherType.SUNNY;
        }

        if (cloudCover <= 60.0) {
            return WeatherType.PARTLY_SUNNY;
        }

        return WeatherType.CLOUDY;
    }

    private Double metersPerSecondToKnots(Double value) {
        if (value == null) {
            return null;
        }

        return roundToOneDecimal(value * 1.94384);
    }

    private Double metersToFeet(Double value) {
        if (value == null) {
            return null;
        }

        return roundToOneDecimal(value * 3.28084);
    }

    private Double celsiusToFahrenheit(Double value) {
        if (value == null) {
            return null;
        }

        return roundToOneDecimal((value * 9.0 / 5.0) + 32.0);
    }

    private Double normalizeDegrees(Double value) {
        if (value == null) {
            return null;
        }

        double normalized = value % 360.0;
        if (normalized < 0) {
            normalized += 360.0;
        }
        return roundToOneDecimal(normalized);
    }

    private Double roundToOneDecimal(Double value) {
        if (value == null) {
            return null;
        }

        return Math.round(value * 10.0) / 10.0;
    }

    private JsonNode getJson(String url, Map<String, String> params) throws Exception {
        return getJson(url, params, null);
    }

    private JsonNode getJson(String url, Map<String, String> params, Map<String, String> headers) throws Exception {
        StringBuilder fullUrl = new StringBuilder(url);
        if (params != null && !params.isEmpty()) {
            fullUrl.append('?');
            boolean first = true;
            for (Map.Entry<String, String> entry : params.entrySet()) {
                if (!first) {
                    fullUrl.append('&');
                }
                fullUrl.append(URLEncoder.encode(entry.getKey(), StandardCharsets.UTF_8));
                fullUrl.append('=');
                fullUrl.append(URLEncoder.encode(entry.getValue(), StandardCharsets.UTF_8));
                first = false;
            }
        }

        HttpURLConnection connection = (HttpURLConnection) URI.create(fullUrl.toString()).toURL().openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                connection.setRequestProperty(entry.getKey(), entry.getValue());
            }
        }

        int statusCode = connection.getResponseCode();
        if (statusCode < 200 || statusCode >= 300) {
            String errorBody = readResponseBody(connection, true);
            throw new IOException("http " + statusCode + " from " + url + ": " + errorBody);
        }

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            return OBJECT_MAPPER.readTree(response.toString());
        }
    }

    private String readResponseBody(HttpURLConnection connection, boolean errorStream) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
            errorStream && connection.getErrorStream() != null ? connection.getErrorStream() : connection.getInputStream(),
            StandardCharsets.UTF_8
        ))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        } catch (IOException e) {
            return "";
        }
    }
}