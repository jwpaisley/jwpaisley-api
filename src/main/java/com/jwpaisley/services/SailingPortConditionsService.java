package com.jwpaisley.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jwpaisley.helpers.CacheHelper;
import com.jwpaisley.helpers.LoggingHelper;
import com.jwpaisley.models.SailingPort;
import com.jwpaisley.models.SailingPortConditions;
import com.jwpaisley.models.WeatherType;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SailingPortConditionsService {
    private static final SailingPortConditionsService INSTANCE = new SailingPortConditionsService();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String NOAA_TIDES_URL = "https://api.tidesandcurrents.noaa.gov/api/prod/datagetter";
    private static final String NOAA_BUOY_URL = "https://www.ndbc.noaa.gov/data/realtime2/";
    private static final String NWS_URL = "https://api.weather.gov/gridpoints";
    private static final String NWS_BASE_URL = "https://api.weather.gov";
    private final CacheHelper cacheHelper = new CacheHelper();

    /**
     * Known NWS Marine Zone IDs for enclosed/sheltered bays, sounds, and harbors
     * where ocean swell model grids leak open-ocean data.
     */
    private static final Set<String> SHELTERED_BAY_ZONES = Set.of(
        // San Francisco Bay / San Pablo Bay / Suisun Bay
        "PZZ530", "PZZ531", "PZZ535",
        // Puget Sound / Hood Canal / San Juan Islands
        "PZZ130", "PZZ131", "PZZ132", "PZZ133", "PZZ134", "PZZ135",
        // Long Island Sound / NY Harbor
        "ANZ330", "ANZ335", "ANZ338",
        // Chesapeake Bay & Delaware Bay
        "ANZ530", "ANZ531", "ANZ532", "ANZ533", "ANZ534", "ANZ540"
    );

    private SailingPortConditionsService() {}

    public static SailingPortConditionsService getInstance() {
        return INSTANCE;
    }

    public SailingPortConditions getConditions(SailingPort port) {
        if (port == null) {
            return null;
        }

        return cacheHelper.get(CacheHelper.CacheType.SAILING_CONDITIONS_CACHE, port.id().toString(), () -> fetchConditions(port));
    }

    public SailingPortConditions refreshConditions(SailingPort port) {
        if (port == null) {
            return null;
        }

        SailingPortConditions refreshed = fetchConditions(port);
        cacheHelper.put(CacheHelper.CacheType.SAILING_CONDITIONS_CACHE, port.id().toString(), refreshed);
        return refreshed;
    }

    public void invalidate(UUID portId) {
        cacheHelper.invalidate(CacheHelper.CacheType.SAILING_CONDITIONS_CACHE, portId.toString());
    }

    private SailingPortConditions fetchConditions(SailingPort port) {
        try {
            Double windSpeed = fetchWindSpeed(port);
            String windDirection = fetchWindDirection(port);
            Double gustSpeed = fetchGustSpeed(port);
            Double waveSize = fetchWaveSize(port);
            Double currentSpeed = fetchCurrentSpeed(port);
            Double waterTemperature = fetchWaterTemperature(port);
            String currentTide = fetchCurrentTide(port);
            Double tideSizeFeet = fetchTideSizeFeet(port);
            Double airTemperature = fetchAirTemperature(port);
            WeatherType weather = fetchWeather(port);
            String marineAlert = fetchMarineAlert(port);

            SailingPortConditions conditions = new SailingPortConditions(
                windSpeed,
                windDirection,
                gustSpeed,
                waveSize,
                currentSpeed,
                waterTemperature,
                currentTide,
                tideSizeFeet,
                airTemperature,
                weather,
                marineAlert,
                Instant.now().toString()
            );
            return conditions;
        } catch (Exception e) {
            LoggingHelper.debug("failed to fetch conditions for port " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
            SailingPortConditions cached = cacheHelper.get(
                CacheHelper.CacheType.SAILING_CONDITIONS_CACHE,
                port.id().toString(),
                () -> null
            );
            if (cached != null) {
                return cached;
            }

            return new SailingPortConditions(
                null,
                null,
                null,
                null,
                null,
                null,
                "slack",
                null,
                null,
                WeatherType.CLOUDY,
                "no marine alerts",
                Instant.now().toString()
            );
        }
    }

    private Double fetchWindSpeed(SailingPort port) throws Exception {
        if (port.nwsOffice() == null || port.nwsGridX() == null || port.nwsGridY() == null) {
            return null;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "jwpaisley/1.0 (jwpaisley@domain.com)");
        
        String url = NWS_URL + "/" + port.nwsOffice() + "/" + port.nwsGridX() + "," + port.nwsGridY() + "/forecast/hourly";
        JsonNode root = getJson(url, null, headers);
        
        JsonNode periods = root.path("properties").path("periods");
        if (!periods.isArray() || periods.isEmpty()) {
            return null;
        }

        JsonNode first = periods.get(0);
        String windSpeedStr = first.path("windSpeed").asText(null);
        if (windSpeedStr == null || windSpeedStr.isBlank()) {
            return null;
        }

        List<Double> numbers = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d+").matcher(windSpeedStr);
        while (matcher.find()) {
            numbers.add(Double.parseDouble(matcher.group()));
        }

        if (numbers.isEmpty()) {
            return null;
        }

        double windMph = numbers.get(numbers.size() - 1);
        return roundToOneDecimal(windMph * 0.868976);
    }

    private String fetchWindDirection(SailingPort port) throws Exception {
        if (port.nwsOffice() == null || port.nwsGridX() == null || port.nwsGridY() == null) {
            LoggingHelper.debug("missing nws grid coordinates for port: " + port.name().toLowerCase());
            return null;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "jwpaisley/1.0 (jwpaisley@domain.com)");

        try {
            String url = NWS_URL + "/" + port.nwsOffice() + "/" + port.nwsGridX() + "," + port.nwsGridY() + "/forecast/hourly";
            JsonNode root = getJson(url, null, headers);

            JsonNode periods = root.path("properties").path("periods");
            if (!periods.isArray() || periods.isEmpty()) {
                LoggingHelper.debug("no hourly periods returned for port: " + port.name().toLowerCase());
                return null;
            }

            JsonNode first = periods.get(0);
            String direction = first.path("windDirection").asText(null);

            if (direction == null || direction.isBlank()) {
                LoggingHelper.debug("wind direction data missing from the nws payload for port: " + port.name().toLowerCase());
                return null;
            }

            return direction.trim().toUpperCase();

        } catch (Exception e) {
            LoggingHelper.debug("failed to fetch wind direction for " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
            return null;
        }
    }

    private Double fetchGustSpeed(SailingPort port) throws Exception {
        if (port.nwsOffice() == null || port.nwsGridX() == null || port.nwsGridY() == null) {
            return null;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "jwpaisley/1.0 (jwpaisley@domain.com)");

        String url = NWS_URL + "/" + port.nwsOffice() + "/" + port.nwsGridX() + "," + port.nwsGridY() + "/forecast/hourly";
        JsonNode root = getJson(url, null, headers);

        JsonNode periods = root.path("properties").path("periods");
        if (!periods.isArray() || periods.isEmpty()) {
            return null;
        }

        JsonNode first = periods.get(0);
        String gustSpeedStr = first.path("windGust").asText(null);

        // Fallback to sustained wind if NWS omits windGust
        if (gustSpeedStr == null || gustSpeedStr.isBlank()) {
            gustSpeedStr = first.path("windSpeed").asText(null);
        }

        if (gustSpeedStr == null || gustSpeedStr.isBlank()) {
            LoggingHelper.debug("gust speed data missing from the nws payload for port: " + port.name().toLowerCase());
            return null;
        }

        List<Double> numbers = new ArrayList<>();
        Matcher matcher = Pattern.compile("\\d+").matcher(gustSpeedStr);
        while (matcher.find()) {
            numbers.add(Double.parseDouble(matcher.group()));
        }

        if (numbers.isEmpty()) {
            return null;
        }

        double gustMph = numbers.get(numbers.size() - 1);
        return roundToOneDecimal(gustMph * 0.868976);
    }

    /**
     * Calculates local significant wind chop height (in feet) dynamically from sustained wind 
     * and gust speeds using a quadratic SMB fetch-limited approximation.
     */
    private Double fetchWaveSize(SailingPort port) {
        try {
            Double sustainedKnots = fetchWindSpeed(port);
            Double gustKnots = fetchGustSpeed(port);

            if (sustainedKnots == null || sustainedKnots <= 0) {
                return 0.5; // Baseline calm water default
            }

            double u = sustainedKnots;
            double g = (gustKnots != null && gustKnots > u) ? gustKnots : u;

            // Weight sustained wind heavily; gusts contribute 25% of their excess to wave building
            double effectiveWind = u + (0.25 * (g - u));

            // Quadratic coastal engineering curve calibrated for ~7nm bay fetch
            double calculatedChop = (0.08 * effectiveWind) + (0.003 * Math.pow(effectiveWind, 2));

            // Floor at 0.5 ft for baseline harbor ripple
            double finalChop = Math.max(0.5, calculatedChop);

            LoggingHelper.debug("calculated bay chop for " + port.name().toLowerCase() 
                    + ": " + roundToOneDecimal(finalChop) + "ft (u=" + u + ", g=" + g + ")");

            return roundToOneDecimal(finalChop);

        } catch (Exception e) {
            LoggingHelper.debug("could not estimate wind chop for " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
            return 0.5;
        }
    }

    private String fetchNwsZoneId(SailingPort port) {
        if (port.latitude() == null || port.longitude() == null) {
            return null;
        }

        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "jwpaisley/1.0 (jwpaisley@domain.com)");

            String url = NWS_BASE_URL + "/points/" + port.latitude() + "," + port.longitude();
            JsonNode root = getJson(url, null, headers);

            String forecastZoneUrl = root.path("properties").path("forecastZone").asText(null);
            if (forecastZoneUrl != null && !forecastZoneUrl.isBlank()) {
                return forecastZoneUrl.substring(forecastZoneUrl.lastIndexOf('/') + 1);
            }
        } catch (Exception e) {
            LoggingHelper.debug("could not determine nws zone id for port " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
        }
        return null;
    }

    private Double fetchWaveSizeFromBuoy(SailingPort port) {
        if (port.buoyStationId() == null || port.buoyStationId().isBlank()) {
            LoggingHelper.debug("buoy station id is missing for port: " + port.name().toLowerCase());
            return null;
        }

        try {
            URL url = URI.create(NOAA_BUOY_URL + port.buoyStationId() + ".txt").toURL();
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(8000);
            connection.setReadTimeout(8000);

            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null && lines.size() < 3) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty()) {
                        lines.add(trimmed);
                    }
                }
            }

            if (lines.size() < 3) {
                LoggingHelper.debug("ndbc file returned fewer than 3 lines for buoy: " + port.buoyStationId().toLowerCase());
                return null;
            }

            String headerLine = lines.get(0).startsWith("#") ? lines.get(0).substring(1).trim() : lines.get(0);
            String[] headers = headerLine.split("\\s+");

            int wvhtIndex = -1;
            for (int i = 0; i < headers.length; i++) {
                if ("WVHT".equalsIgnoreCase(headers[i])) {
                    wvhtIndex = i;
                    break;
                }
            }

            if (wvhtIndex == -1) {
                LoggingHelper.debug("wvht column not found in header for buoy: " + port.buoyStationId().toLowerCase());
                return null;
            }

            String[] latestData = lines.get(2).split("\\s+");
            if (wvhtIndex >= latestData.length) {
                LoggingHelper.debug("wvht column index out of bounds in data row for buoy: " + port.buoyStationId().toLowerCase());
                return null;
            }

            String rawValue = latestData[wvhtIndex];
            LoggingHelper.debug("raw wvht value for " + port.name().toLowerCase() + " (" + port.buoyStationId().toLowerCase() + "): " + rawValue.toLowerCase());

            if ("99".equals(rawValue) || "99.0".equals(rawValue) || "99.00".equals(rawValue) 
                    || "999".equals(rawValue) || "999.0".equals(rawValue) || "MM".equalsIgnoreCase(rawValue)) {
                LoggingHelper.debug("buoy " + port.buoyStationId().toLowerCase() + " does not report active wave height data.");
                return null;
            }

            Double waveMeters = parseNumericValue(rawValue);
            if (waveMeters == null) {
                return null;
            }

            return roundToOneDecimal(waveMeters * 3.28084);

        } catch (Exception e) {
            LoggingHelper.debug("failed to retrieve buoy data for " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
            return null;
        }
    }

    private Double fetchWaveSizeFromNwsGrid(SailingPort port) {
        if (port.nwsOffice() == null || port.nwsGridX() == null || port.nwsGridY() == null) {
            return null;
        }

        try {
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", "jwpaisley/1.0 (jwpaisley@domain.com)");

            String url = NWS_URL + "/" + port.nwsOffice() + "/" + port.nwsGridX() + "," + port.nwsGridY();
            JsonNode root = getJson(url, null, headers);

            JsonNode waveValues = root.path("properties").path("waveHeight").path("values");
            if (waveValues.isArray() && !waveValues.isEmpty()) {
                JsonNode latest = waveValues.get(0);
                if (latest.hasNonNull("value")) {
                    double waveMeters = latest.path("value").asDouble();
                    return roundToOneDecimal(waveMeters * 3.28084);
                }
            }
        } catch (Exception e) {
            LoggingHelper.debug("failed to retrieve grid wave height for " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
        }
        return null;
    }

    private Double calculateBayWindChop(SailingPort port) {
        try {
            Double windKnots = fetchWindSpeed(port);
            if (windKnots != null && windKnots > 0) {
                double estimatedChop = Math.max(0.5, windKnots * 0.12);
                return roundToOneDecimal(estimatedChop);
            }
        } catch (Exception e) {
            LoggingHelper.debug("could not estimate wind chop for " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
        }
        return 0.5;
    }

    private Double fetchWaterTemperature(SailingPort port) throws Exception {
        if (port.buoyStationId() == null || port.buoyStationId().isBlank()) {
            LoggingHelper.debug("buoy station id is missing for port: " + port.name().toLowerCase());
            return null;
        }

        URL url = URI.create(NOAA_BUOY_URL + port.buoyStationId() + ".txt").toURL();
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(10000);

        List<String> lines = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null && lines.size() < 3) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    lines.add(trimmed);
                }
            }
        }

        if (lines.size() < 3) {
            return null;
        }

        String headerLine = lines.get(0).startsWith("#") ? lines.get(0).substring(1).trim() : lines.get(0);
        String[] headers = headerLine.split("\\s+");

        int wtmpIndex = -1;
        for (int i = 0; i < headers.length; i++) {
            if ("WTMP".equalsIgnoreCase(headers[i])) {
                wtmpIndex = i;
                break;
            }
        }

        if (wtmpIndex == -1) {
            return null;
        }

        String[] latestData = lines.get(2).split("\\s+");
        if (wtmpIndex < latestData.length) {
            String rawValue = latestData[wtmpIndex];

            if ("99".equals(rawValue) || "99.0".equals(rawValue) || "99.00".equals(rawValue) 
                    || "999".equals(rawValue) || "999.0".equals(rawValue) || "MM".equalsIgnoreCase(rawValue)) {
                LoggingHelper.debug("buoy " + port.buoyStationId().toLowerCase() + " does not report active water temperature data.");
                return null;
            }

            Double celsius = parseNumericValue(rawValue);
            if (celsius == null) {
                return null;
            }

            Double fahrenheit = celsius * 1.8 + 32.0;
            return roundToOneDecimal(fahrenheit);
        }

        return null;
    }

    private Double fetchCurrentSpeed(SailingPort port) {
        if (port.currentStationId() == null || port.currentStationId().isBlank()) {
            LoggingHelper.debug("current station id is missing for port: " + port.name().toLowerCase());
            return null;
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("station", port.currentStationId());
        params.put("product", "currents");
        params.put("time_zone", "lst_ldt");
        params.put("units", "english");
        params.put("format", "json");
        params.put("date", "latest");

        try {
            JsonNode root = getJson(NOAA_TIDES_URL, params);
            JsonNode data = root.path("data");

            if (data.isArray() && !data.isEmpty()) {
                JsonNode latest = data.get(0);
                String speedStr = latest.path("s").asText(null);

                if (speedStr != null && !speedStr.isBlank()) {
                    Double speedKnots = parseNumericValue(speedStr);
                    if (speedKnots != null) {
                        LoggingHelper.debug("fetched live current speed for " + port.name().toLowerCase()
                                + " (" + port.currentStationId().toLowerCase() + "): " + speedKnots + " kts");
                        return roundToOneDecimal(speedKnots);
                    }
                }
            }
        } catch (Exception e) {
            LoggingHelper.debug("failed to fetch current speed for " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
        }

        return null;
    }

    private String fetchCurrentTide(SailingPort port) throws Exception {
        if (port.tideStationId() == null || port.tideStationId().isBlank()) {
            return "slack";
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("station", port.tideStationId());
        params.put("product", "predictions");
        params.put("datum", "MLLW");
        params.put("time_zone", "lst_ldt");
        params.put("units", "english");
        params.put("interval", "hilo");
        params.put("range", "2");
        params.put("format", "json");

        JsonNode root = getJson(NOAA_TIDES_URL, params);
        JsonNode predictions = root.path("predictions");
        if (!predictions.isArray() || predictions.isEmpty()) {
            return "slack";
        }

        JsonNode first = predictions.get(0);
        String type = first.path("type").asText(null);
        if (type == null || type.isBlank()) {
            return "slack";
        }
        String normalized = type.toLowerCase();
        if (normalized.contains("h")) {
            return "flood";
        }
        if (normalized.contains("l")) {
            return "ebb";
        }
        return "slack";
    }

    private Double fetchTideSizeFeet(SailingPort port) throws Exception {
        if (port.tideStationId() == null || port.tideStationId().isBlank()) {
            return null;
        }

        Map<String, String> params = new LinkedHashMap<>();
        params.put("station", port.tideStationId());
        params.put("product", "predictions");
        params.put("datum", "MLLW");
        params.put("time_zone", "lst_ldt");
        params.put("units", "english");
        params.put("interval", "hilo");
        params.put("range", "2");
        params.put("format", "json");

        JsonNode root = getJson(NOAA_TIDES_URL, params);
        JsonNode predictions = root.path("predictions");
        if (!predictions.isArray() || predictions.isEmpty()) {
            return null;
        }

        JsonNode first = predictions.get(0);
        String value = first.path("v").asText(null);
        if (value == null || value.isBlank()) {
            return null;
        }

        return roundToOneDecimal(parseNumericValue(value));
    }

    private Double fetchAirTemperature(SailingPort port) {
        if (port.nwsOffice() == null || port.nwsGridX() == null || port.nwsGridY() == null) {
            LoggingHelper.debug("missing nws grid coordinates for port: " + port.name().toLowerCase());
            return null;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "jwpaisley/1.0 (jwpaisley@domain.com)");

        try {
            // 1. Try real-time surface observation station if assigned
            if (port.observationStationId() != null && !port.observationStationId().isBlank()) {
                String stationUrl = NWS_BASE_URL + "/stations/" + port.observationStationId().toUpperCase() + "/observations/latest";
                JsonNode obsRoot = getJson(stationUrl, null, headers);
                JsonNode tempValueNode = obsRoot.path("properties").path("temperature").path("value");

                if (tempValueNode.isNumber()) {
                    double tempCelsius = tempValueNode.asDouble();
                    double tempFahrenheit = (tempCelsius * 9.0 / 5.0) + 32.0;
                    LoggingHelper.debug("fetched live surface temp for " + port.name().toLowerCase() + ": " + roundToOneDecimal(tempFahrenheit) + "f");
                    return roundToOneDecimal(tempFahrenheit);
                }
            }

            // 2. Fallback to /forecast/hourly grid if observation station is omitted or fails
            String forecastUrl = NWS_URL + "/" + port.nwsOffice() + "/" + port.nwsGridX() + "," + port.nwsGridY() + "/forecast/hourly";
            JsonNode root = getJson(forecastUrl, null, headers);
            JsonNode periods = root.path("properties").path("periods");

            if (!periods.isArray() || periods.isEmpty()) {
                LoggingHelper.debug("no hourly periods returned for port: " + port.name().toLowerCase());
                return null;
            }

            JsonNode currentHour = periods.get(0);
            JsonNode tempNode = currentHour.path("temperature");

            if (tempNode.isMissingNode() || tempNode.isNull()) {
                return null;
            }

            double tempValue = tempNode.asDouble();
            String unit = currentHour.path("temperatureUnit").asText("F");

            if ("C".equalsIgnoreCase(unit)) {
                tempValue = (tempValue * 9.0 / 5.0) + 32.0;
            }

            return roundToOneDecimal(tempValue);

        } catch (Exception e) {
            LoggingHelper.debug("failed to fetch air temperature for " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
            return null;
        }
    }

    private WeatherType fetchWeather(SailingPort port) throws Exception {
        if (port.nwsOffice() == null || port.nwsGridX() == null || port.nwsGridY() == null) {
            LoggingHelper.debug("missing nws grid coordinates for port: " + port.name().toLowerCase());
            return WeatherType.CLOUDY;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "jwpaisley/1.0 (jwpaisley@domain.com)");

        String url = NWS_URL + "/" + port.nwsOffice() + "/" + port.nwsGridX() + "," + port.nwsGridY() + "/forecast/hourly";
        
        try {
            JsonNode root = getJson(url, null, headers);
            JsonNode periods = root.path("properties").path("periods");

            if (!periods.isArray() || periods.isEmpty()) {
                LoggingHelper.debug("no forecast periods returned for port: " + port.name().toLowerCase());
                return WeatherType.CLOUDY;
            }

            JsonNode first = periods.get(0);
            String shortForecast = first.path("shortForecast").asText(null);
            LoggingHelper.debug("short forecast for " + port.name().toLowerCase() + ": " + (shortForecast != null ? shortForecast.toLowerCase() : "null"));

            return WeatherType.fromText(shortForecast);

        } catch (Exception e) {
            LoggingHelper.debug("failed to fetch weather forecast for " + port.name().toLowerCase() + ": " + e.getMessage().toLowerCase());
            return WeatherType.CLOUDY;
        }
    }

    private Double parseNumericValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        int index = normalized.indexOf(' ');
        if (index >= 0) {
            normalized = normalized.substring(0, index);
        }

        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double roundToOneDecimal(Double value) {
        if (value == null) {
            return null;
        }

        return Math.round(value * 10.0) / 10.0;
    }

    private String fetchMarineAlert(SailingPort port) throws Exception {
        if (port.latitude() == null || port.longitude() == null) {
            return "no marine alerts";
        }

        String encodedPoint = URLEncoder.encode(port.latitude() + "," + port.longitude(), StandardCharsets.UTF_8);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "jwpaisley/1.0 (jwpaisley@domain.com)");
        JsonNode root = getJson(NWS_BASE_URL + "/alerts/active?point=" + encodedPoint, null, headers);
        JsonNode features = root.path("features");
        if (!features.isArray() || features.isEmpty()) {
            return "no marine alerts";
        }

        return "active alerts present";
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

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }

            return OBJECT_MAPPER.readTree(response.toString());
        }
    }
}