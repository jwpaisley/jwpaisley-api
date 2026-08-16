package com.jwpaisley.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jwpaisley.helpers.ApiSportsHelper;
import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.LoggingHelper;
import com.jwpaisley.helpers.TimeHelper;
import com.jwpaisley.models.Sport;
import com.jwpaisley.models.SportsPredictionFixture;
import com.jwpaisley.services.DatabaseService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import javax.sql.DataSource;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SportsPredictionFixturesController {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private enum FixtureScope {
        ALL,
        PAST,
        UPCOMING
    }

    private record LeagueSyncTarget(int apiSportsLeagueId, int apiSportsSeasonId, Sport sport) {}

    private record SyncResult(int fixturesSynced, int teamsSynced) {}
    public static SportsPredictionFixture sportsPredictionFixtureFromResultSet(ResultSet rs) throws SQLException {
        return new SportsPredictionFixture(
            rs.getObject("id", UUID.class),
            rs.getInt("api_sports_fixture_id"),
            rs.getInt("api_sports_league_id"),
            rs.getInt("api_sports_season_id"),
            rs.getObject("home_team_id", UUID.class),
            rs.getObject("away_team_id", UUID.class),
            TimeHelper.toUtcIsoString(rs.getTimestamp("commence_time")),
            rs.getObject("home_odds", Double.class),
            rs.getObject("away_odds", Double.class),
            rs.getObject("draw_odds", Double.class),
            rs.getString("status"),
            rs.getObject("home_score", Integer.class),
            rs.getObject("away_score", Integer.class),
            rs.getObject("winning_team_id", UUID.class),
            TimeHelper.toUtcIsoString(rs.getTimestamp("created_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sports prediction fixtures");
    }

    private FixtureScope parseScope(Context ctx) {
        String rawScope = ctx.queryParam("scope");
        if (rawScope == null || rawScope.isBlank()) {
            return FixtureScope.ALL;
        }

        try {
            return FixtureScope.valueOf(rawScope.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.status(400).result("Invalid scope. Expected one of: ALL, PAST, UPCOMING");
            return null;
        }
    }

    private int[] fetchApiLeagueAndSeasonByLeagueId(UUID leagueId) throws SQLException {
        String sql = "SELECT api_sports_league_id, api_sports_season_id FROM sports_prediction_leagues WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, leagueId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundResponse("Sports prediction league not found");
                }

                return new int[] { rs.getInt("api_sports_league_id"), rs.getInt("api_sports_season_id") };
            }
        }
    }

    public void getAll(Context ctx) {
        List<SportsPredictionFixture> fixtures = new ArrayList<>();
        String sql = "SELECT * FROM sports_prediction_fixtures ORDER BY commence_time DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                fixtures.add(sportsPredictionFixtureFromResultSet(rs));
            }
            ctx.json(fixtures);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

        public void getForLeague(Context ctx) {
                UUID leagueId = UUID.fromString(ctx.pathParam("leagueId"));
                FixtureScope scope = parseScope(ctx);
                if (scope == null) {
                        return;
                }

                List<SportsPredictionFixture> fixtures = new ArrayList<>();

                String sql = """
                        SELECT *
                        FROM sports_prediction_fixtures
                        WHERE api_sports_league_id = ?
                            AND api_sports_season_id = ?
                            AND (
                                ? = 'ALL'
                                OR (
                                    ? = 'PAST'
                                    AND (
                                        (commence_time IS NOT NULL AND commence_time < CURRENT_TIMESTAMP)
                                        OR status IN ('IN_PROGRESS', 'FINISHED')
                                    )
                                )
                                OR (
                                    ? = 'UPCOMING'
                                    AND (
                                        (commence_time IS NULL OR commence_time >= CURRENT_TIMESTAMP)
                                        AND COALESCE(status, '') NOT IN ('IN_PROGRESS', 'FINISHED')
                                    )
                                )
                            )
                        ORDER BY commence_time ASC NULLS LAST
                """;

                DataSource ds = DatabaseService.getInstance().getDataSource();

                try {
                        int[] leagueInfo = fetchApiLeagueAndSeasonByLeagueId(leagueId);

                        try (Connection conn = ds.getConnection();
                                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                                pstmt.setInt(1, leagueInfo[0]);
                                pstmt.setInt(2, leagueInfo[1]);
                                pstmt.setString(3, scope.name());
                                pstmt.setString(4, scope.name());
                                pstmt.setString(5, scope.name());

                                try (ResultSet rs = pstmt.executeQuery()) {
                                        while (rs.next()) {
                                                fixtures.add(sportsPredictionFixtureFromResultSet(rs));
                                        }
                                }
                        }

                        ctx.json(fixtures);
                } catch (SQLException e) {
                        handleError(ctx, e);
                }
        }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM sports_prediction_fixtures WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionFixtureFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction fixture not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    private Timestamp toSqlTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        try {
            return Timestamp.from(Instant.parse(value));
        } catch (Exception e) {
            try {
                return Timestamp.valueOf(value.replace("Z", ""));
            } catch (Exception ignored) {
                return null;
            }
        }
    }

    private String normalizeFixtureStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "SCHEDULED";
        }

        String normalized = rawStatus.trim().replace(' ', '_').toUpperCase(Locale.ROOT);
        if (normalized.contains("FINISHED") || normalized.contains("COMPLETED")) {
            return "FINISHED";
        }
        if (normalized.contains("IN_PROGRESS") || normalized.contains("LIVE") || normalized.contains("HALF")) {
            return "IN_PROGRESS";
        }
        if (normalized.contains("SCHEDULED") || normalized.contains("NOT_STARTED") || normalized.contains("TIMED")) {
            return "SCHEDULED";
        }
        return normalized;
    }

    private List<LeagueSyncTarget> fetchActiveLeagueTargets() throws SQLException {
        String sql = """
            SELECT DISTINCT api_sports_league_id, api_sports_season_id, sport
            FROM sports_prediction_leagues
            WHERE league_end_date IS NULL OR league_end_date >= CURRENT_TIMESTAMP
            ORDER BY api_sports_league_id, api_sports_season_id
        """;

        List<LeagueSyncTarget> targets = new ArrayList<>();
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                targets.add(new LeagueSyncTarget(
                    rs.getInt("api_sports_league_id"),
                    rs.getInt("api_sports_season_id"),
                    Sport.valueOf(rs.getString("sport"))
                ));
            }
        }

        return targets;
    }

    private JsonNode fetchJsonFromApiSport(Sport sport, String path) throws Exception {
        HttpRequest request = ApiSportsHelper.buildRequestForSport(sport, path).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new IllegalStateException("API Sports request failed with status " + response.statusCode() + " for path: " + path);
        }

        return OBJECT_MAPPER.readTree(response.body());
    }

    private String fetchTeamCodeFromApi(Sport sport, int apiSportsTeamId) {
        try {
            JsonNode teamsRoot = fetchJsonFromApiSport(sport, "/teams?id=" + apiSportsTeamId);
            JsonNode responseNode = teamsRoot.path("response");
            if (!responseNode.isArray() || responseNode.isEmpty()) {
                return null;
            }

            JsonNode firstNode = responseNode.get(0);
            String code = firstNode.path("team").path("code").asText(null);
            if (code == null || code.isBlank()) {
                code = firstNode.path("team").path("abbreviation").asText(null);
            }
            if (code == null || code.isBlank()) {
                code = firstNode.path("code").asText(null);
            }

            return (code == null || code.isBlank()) ? null : code;
        } catch (Exception e) {
            LoggingHelper.warning("Failed to fetch team code for apiSportsTeamId=" + apiSportsTeamId + ": " + e.getMessage());
            return null;
        }
    }

    private UUID ensureTeamExists(JsonNode teamNode, int fixtureId, Sport sport) throws SQLException {
        if (teamNode == null || teamNode.isNull()) {
            return null;
        }

        int apiSportsTeamId = teamNode.path("id").asInt(0);
        if (apiSportsTeamId <= 0) {
            return null;
        }

        String name = teamNode.path("name").asText(null);
        String code = teamNode.path("code").asText(null);
        String logoUrl = teamNode.path("logo").asText(null);

        if (code == null || code.isBlank()) {
            code = fetchTeamCodeFromApi(sport, apiSportsTeamId);
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            String selectSql = "SELECT id, name, code, logo_url FROM sports_prediction_teams WHERE api_sports_team_id = ?";
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                selectStmt.setInt(1, apiSportsTeamId);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (rs.next()) {
                        UUID id = rs.getObject("id", UUID.class);
                        boolean shouldUpdate = false;
                        if (name != null && !name.equals(rs.getString("name"))) {
                            shouldUpdate = true;
                        }
                        if ((code == null && rs.getString("code") != null) || (code != null && !code.equals(rs.getString("code")))) {
                            shouldUpdate = true;
                        }
                        if ((logoUrl == null && rs.getString("logo_url") != null) || (logoUrl != null && !logoUrl.equals(rs.getString("logo_url")))) {
                            shouldUpdate = true;
                        }

                        if (shouldUpdate) {
                            String updateSql = "UPDATE sports_prediction_teams SET name = ?, code = ?, logo_url = ? WHERE api_sports_team_id = ? RETURNING id";
                            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                                updateStmt.setString(1, name != null ? name : rs.getString("name"));
                                updateStmt.setString(2, code);
                                updateStmt.setString(3, logoUrl);
                                updateStmt.setInt(4, apiSportsTeamId);
                                try (ResultSet updateRs = updateStmt.executeQuery()) {
                                    if (updateRs.next()) {
                                        return updateRs.getObject("id", UUID.class);
                                    }
                                }
                            }
                        }
                        return id;
                    }
                }
            }

            String insertSql = "INSERT INTO sports_prediction_teams (api_sports_team_id, name, code, logo_url) VALUES (?, ?, ?, ?) RETURNING id";
            try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                insertStmt.setInt(1, apiSportsTeamId);
                insertStmt.setString(2, name);
                insertStmt.setString(3, code);
                insertStmt.setString(4, logoUrl);
                try (ResultSet insertRs = insertStmt.executeQuery()) {
                    if (insertRs.next()) {
                        return insertRs.getObject("id", UUID.class);
                    }
                }
            }
        }

        LoggingHelper.warning("Failed to upsert team for fixture " + fixtureId + " (apiSportsTeamId=" + apiSportsTeamId + ")");
        return null;
    }

    private Double parseOddValue(String oddText) {
        if (oddText == null || oddText.isBlank() || "-".equals(oddText)) {
            return null;
        }

        String normalized = oddText.trim().replace(',', '.');
        try {
            return Double.parseDouble(normalized);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private String resolveSelectionKey(String label, String homeTeamName, String awayTeamName) {
        if (label == null || label.isBlank()) {
            return null;
        }

        String normalizedLabel = label.trim().toLowerCase(Locale.ROOT);
        String normalizedHome = homeTeamName == null ? "" : homeTeamName.trim().toLowerCase(Locale.ROOT);
        String normalizedAway = awayTeamName == null ? "" : awayTeamName.trim().toLowerCase(Locale.ROOT);

        if (!normalizedHome.isBlank() && normalizedLabel.equals(normalizedHome)) {
            return "home";
        }

        if (!normalizedAway.isBlank() && normalizedLabel.equals(normalizedAway)) {
            return "away";
        }

        if (
            normalizedLabel.equals("1")
            || normalizedLabel.equals("home")
            || normalizedLabel.contains("home team")
            || normalizedLabel.equals("team1")
            || normalizedLabel.equals("team 1")
        ) {
            return "home";
        }

        if (
            normalizedLabel.equals("2")
            || normalizedLabel.equals("away")
            || normalizedLabel.contains("away team")
            || normalizedLabel.equals("team2")
            || normalizedLabel.equals("team 2")
        ) {
            return "away";
        }

        if (normalizedLabel.equals("x") || normalizedLabel.contains("draw") || normalizedLabel.contains("tie")) {
            return "draw";
        }

        return null;
    }

    private boolean isPrimaryMatchWinnerMarket(String betName) {
        if (betName == null || betName.isBlank()) {
            return false;
        }

        String normalized = betName.toLowerCase(Locale.ROOT);
        return normalized.contains("match winner")
            || normalized.contains("1x2")
            || normalized.contains("h2h")
            || normalized.contains("moneyline");
    }

    private void collectOddsFromBookmakersNode(
        JsonNode bookmakersNode,
        String homeTeamName,
        String awayTeamName,
        Map<String, Double> primaryOdds,
        Map<String, Double> fallbackOdds
    ) {
        if (!bookmakersNode.isArray()) {
            return;
        }

        for (JsonNode bookmakerNode : bookmakersNode) {
            JsonNode betsNode = bookmakerNode.path("bets");
            if (!betsNode.isArray()) {
                continue;
            }

            for (JsonNode betNode : betsNode) {
                String betName = betNode.path("name").asText("");
                boolean isPrimaryMarket = isPrimaryMatchWinnerMarket(betName);

                JsonNode valuesNode = betNode.path("values");
                if (!valuesNode.isArray()) {
                    continue;
                }

                for (JsonNode valueNode : valuesNode) {
                    String label = valueNode.path("value").asText(null);
                    String oddText = valueNode.path("odd").asText(null);
                    String key = resolveSelectionKey(label, homeTeamName, awayTeamName);
                    Double oddValue = parseOddValue(oddText);

                    if (key == null || oddValue == null) {
                        continue;
                    }

                    if (isPrimaryMarket) {
                        primaryOdds.putIfAbsent(key, oddValue);
                    } else {
                        fallbackOdds.putIfAbsent(key, oddValue);
                    }
                }
            }
        }
    }

    private Map<String, Double> mergeOddsMaps(Map<String, Double> primaryOdds, Map<String, Double> fallbackOdds) {
        Map<String, Double> odds = new HashMap<>();
        odds.putAll(fallbackOdds);
        odds.putAll(primaryOdds);
        return odds;
    }

    private boolean hasAllMatchWinnerOdds(Map<String, Double> odds) {
        return odds.get("home") != null && odds.get("away") != null && odds.get("draw") != null;
    }

    private Map<String, Double> fetchMatchWinnerOddsFromFixturePayload(JsonNode fixtureNode, String homeTeamName, String awayTeamName) {
        Map<String, Double> primaryOdds = new HashMap<>();
        Map<String, Double> fallbackOdds = new HashMap<>();

        collectOddsFromBookmakersNode(fixtureNode.path("bookmakers"), homeTeamName, awayTeamName, primaryOdds, fallbackOdds);

        JsonNode oddsNode = fixtureNode.path("odds");
        if (oddsNode.isArray()) {
            for (JsonNode oddsEntry : oddsNode) {
                collectOddsFromBookmakersNode(oddsEntry.path("bookmakers"), homeTeamName, awayTeamName, primaryOdds, fallbackOdds);
            }
        }

        return mergeOddsMaps(primaryOdds, fallbackOdds);
    }

    private String buildOddsPath(Sport sport, int fixtureId, int apiSportsLeagueId, int apiSportsSeasonId) {
        StringBuilder path = new StringBuilder("/odds?fixture=")
            .append(fixtureId)
            .append("&bet=1");

        if (sport == Sport.SOCCER) {
            path.append("&league=").append(apiSportsLeagueId);
            path.append("&season=").append(apiSportsSeasonId);
        }

        return path.toString();
    }

    private Map<String, Double> fetchMatchWinnerOdds(
        Sport sport,
        int fixtureId,
        int apiSportsLeagueId,
        int apiSportsSeasonId,
        String homeTeamName,
        String awayTeamName
    ) throws Exception {
        JsonNode oddsRoot = fetchJsonFromApiSport(
            sport,
            buildOddsPath(sport, fixtureId, apiSportsLeagueId, apiSportsSeasonId)
        );
        Map<String, Double> primaryOdds = new HashMap<>();
        Map<String, Double> fallbackOdds = new HashMap<>();

        LoggingHelper.debug("odds for fixture " + fixtureId + ": " + oddsRoot.toString());

        for (JsonNode responseNode : oddsRoot.path("response")) {
            collectOddsFromBookmakersNode(responseNode.path("bookmakers"), homeTeamName, awayTeamName, primaryOdds, fallbackOdds);
        }

        return mergeOddsMaps(primaryOdds, fallbackOdds);
    }

    private void upsertFixtureForApiPayload(JsonNode fixtureNode, int apiSportsLeagueId, int apiSportsSeasonId, UUID homeTeamId, UUID awayTeamId, Map<String, Double> odds) throws SQLException {
        int apiSportsFixtureId = fixtureNode.path("fixture").path("id").asInt(0);
        String commenceTime = fixtureNode.path("fixture").path("date").asText(null);
        String status = normalizeFixtureStatus(fixtureNode.path("fixture").path("status").path("long").asText(null));

        DataSource ds = DatabaseService.getInstance().getDataSource();
        String selectSql = "SELECT id, status FROM sports_prediction_fixtures WHERE api_sports_fixture_id = ? AND api_sports_league_id = ? AND api_sports_season_id = ?";

        try (Connection conn = ds.getConnection();
             PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {

            selectStmt.setInt(1, apiSportsFixtureId);
            selectStmt.setInt(2, apiSportsLeagueId);
            selectStmt.setInt(3, apiSportsSeasonId);

            try (ResultSet rs = selectStmt.executeQuery()) {
                if (rs.next()) {
                    String currentStatus = rs.getString("status");
                    if ("IN_PROGRESS".equals(currentStatus) || "FINISHED".equals(currentStatus)) {
                        return;
                    }

                    String updateSql = """
                        UPDATE sports_prediction_fixtures SET
                            commence_time = ?, home_odds = ?, away_odds = ?, draw_odds = ?, status = ?,
                            home_team_id = ?, away_team_id = ?
                        WHERE id = ?::uuid
                    """;
                    try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                        updateStmt.setTimestamp(1, toSqlTimestamp(commenceTime));
                        updateStmt.setObject(2, odds.getOrDefault("home", null));
                        updateStmt.setObject(3, odds.getOrDefault("away", null));
                        updateStmt.setObject(4, odds.getOrDefault("draw", null));
                        updateStmt.setString(5, status);
                        updateStmt.setObject(6, homeTeamId);
                        updateStmt.setObject(7, awayTeamId);
                        updateStmt.setObject(8, rs.getObject("id", UUID.class));
                        updateStmt.executeUpdate();
                    }
                    return;
                }

                String insertSql = """
                    INSERT INTO sports_prediction_fixtures (
                        api_sports_fixture_id, api_sports_league_id, api_sports_season_id,
                        home_team_id, away_team_id, commence_time, home_odds, away_odds,
                        draw_odds, status
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setInt(1, apiSportsFixtureId);
                    insertStmt.setInt(2, apiSportsLeagueId);
                    insertStmt.setInt(3, apiSportsSeasonId);
                    insertStmt.setObject(4, homeTeamId);
                    insertStmt.setObject(5, awayTeamId);
                    insertStmt.setTimestamp(6, toSqlTimestamp(commenceTime));
                    insertStmt.setObject(7, odds.getOrDefault("home", null));
                    insertStmt.setObject(8, odds.getOrDefault("away", null));
                    insertStmt.setObject(9, odds.getOrDefault("draw", null));
                    insertStmt.setString(10, status);
                    insertStmt.executeUpdate();
                }
            }
        }
    }

    private SyncResult syncLeague(LeagueSyncTarget target) throws Exception {
        String from = LocalDate.now().toString();
        String to = LocalDate.now().plusDays(7).toString();
        JsonNode fixturesRoot = fetchJsonFromApiSport(target.sport(), "/fixtures?league=" + target.apiSportsLeagueId() + "&season=" + target.apiSportsSeasonId() + "&from=" + from + "&to=" + to);

        JsonNode responseNode = fixturesRoot.path("response");
        if (!responseNode.isArray()) {
            return new SyncResult(0, 0);
        }

        int fixtureCount = 0;
        int teamCount = 0;

        for (JsonNode fixtureNode : responseNode) {
            JsonNode homeTeamNode = fixtureNode.path("teams").path("home");
            JsonNode awayTeamNode = fixtureNode.path("teams").path("away");
            String homeTeamName = homeTeamNode.path("name").asText(null);
            String awayTeamName = awayTeamNode.path("name").asText(null);

            UUID homeTeamId = ensureTeamExists(homeTeamNode, fixtureNode.path("fixture").path("id").asInt(0), target.sport());
            UUID awayTeamId = ensureTeamExists(awayTeamNode, fixtureNode.path("fixture").path("id").asInt(0), target.sport());

            if (homeTeamId != null) {
                teamCount++;
            }
            if (awayTeamId != null) {
                teamCount++;
            }

            Map<String, Double> odds = fetchMatchWinnerOddsFromFixturePayload(fixtureNode, homeTeamName, awayTeamName);

            if (!hasAllMatchWinnerOdds(odds)) {
                Map<String, Double> fallbackOdds = fetchMatchWinnerOdds(
                    target.sport(),
                    fixtureNode.path("fixture").path("id").asInt(0),
                    target.apiSportsLeagueId(),
                    target.apiSportsSeasonId(),
                    homeTeamName,
                    awayTeamName
                );

                if (odds.get("home") == null && fallbackOdds.get("home") != null) {
                    odds.put("home", fallbackOdds.get("home"));
                }
                if (odds.get("away") == null && fallbackOdds.get("away") != null) {
                    odds.put("away", fallbackOdds.get("away"));
                }
                if (odds.get("draw") == null && fallbackOdds.get("draw") != null) {
                    odds.put("draw", fallbackOdds.get("draw"));
                }
            }

            upsertFixtureForApiPayload(fixtureNode, target.apiSportsLeagueId(), target.apiSportsSeasonId(), homeTeamId, awayTeamId, odds);
            fixtureCount++;
        }

        return new SyncResult(fixtureCount, teamCount);
    }

    public void syncJob(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !(AuthHelper.isServiceAccount(ctx) || AuthHelper.isAdmin(ctx))) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                List<LeagueSyncTarget> activeTargets = fetchActiveLeagueTargets();
                LoggingHelper.info("Starting fixture sync job for " + activeTargets.size() + " active league(s)");

                int totalFixtures = 0;
                int totalTeams = 0;

                for (LeagueSyncTarget target : activeTargets) {
                    SyncResult result = syncLeague(target);
                    totalFixtures += result.fixturesSynced();
                    totalTeams += result.teamsSynced();
                }

                LoggingHelper.success("Synced " + totalFixtures + " fixtures and " + totalTeams + " teams across " + activeTargets.size() + " leagues");
            } catch (Exception e) {
                LoggingHelper.error("Fixture sync job failed: " + e.getMessage());
            }
        });

        ctx.status(200).result("Fixture sync job started");
    }

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        SportsPredictionFixture newFixture = ctx.bodyAsClass(SportsPredictionFixture.class);
        String sql = """
            INSERT INTO sports_prediction_fixtures (
                api_sports_fixture_id, api_sports_league_id, api_sports_season_id,
                home_team_id, away_team_id, commence_time, home_odds, away_odds,
                draw_odds, status, home_score, away_score, winning_team_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, newFixture.apiSportsFixtureId());
            pstmt.setInt(2, newFixture.apiSportsLeagueId());
            pstmt.setInt(3, newFixture.apiSportsSeasonId());
            pstmt.setObject(4, newFixture.homeTeamId());
            pstmt.setObject(5, newFixture.awayTeamId());
            pstmt.setTimestamp(6, newFixture.commenceTime() != null ? java.sql.Timestamp.valueOf(newFixture.commenceTime().replace("Z", ".000000")) : null);
            pstmt.setObject(7, newFixture.homeOdds());
            pstmt.setObject(8, newFixture.awayOdds());
            pstmt.setObject(9, newFixture.drawOdds());
            pstmt.setString(10, newFixture.status());
            pstmt.setObject(11, newFixture.homeScore());
            pstmt.setObject(12, newFixture.awayScore());
            pstmt.setObject(13, newFixture.winningTeamId());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(sportsPredictionFixtureFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void update(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        SportsPredictionFixture updatedFixture = ctx.bodyAsClass(SportsPredictionFixture.class);
        String sql = """
            UPDATE sports_prediction_fixtures SET
                api_sports_fixture_id = ?, api_sports_league_id = ?, api_sports_season_id = ?,
                home_team_id = ?, away_team_id = ?, commence_time = ?, home_odds = ?,
                away_odds = ?, draw_odds = ?, status = ?, home_score = ?, away_score = ?,
                winning_team_id = ?
            WHERE id = ?::uuid
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, updatedFixture.apiSportsFixtureId());
            pstmt.setInt(2, updatedFixture.apiSportsLeagueId());
            pstmt.setInt(3, updatedFixture.apiSportsSeasonId());
            pstmt.setObject(4, updatedFixture.homeTeamId());
            pstmt.setObject(5, updatedFixture.awayTeamId());
            pstmt.setTimestamp(6, updatedFixture.commenceTime() != null ? java.sql.Timestamp.valueOf(updatedFixture.commenceTime().replace("Z", ".000000")) : null);
            pstmt.setObject(7, updatedFixture.homeOdds());
            pstmt.setObject(8, updatedFixture.awayOdds());
            pstmt.setObject(9, updatedFixture.drawOdds());
            pstmt.setString(10, updatedFixture.status());
            pstmt.setObject(11, updatedFixture.homeScore());
            pstmt.setObject(12, updatedFixture.awayScore());
            pstmt.setObject(13, updatedFixture.winningTeamId());
            pstmt.setObject(14, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionFixtureFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction fixture not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void delete(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "DELETE FROM sports_prediction_fixtures WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Sports prediction fixture not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
