package com.jwpaisley.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jwpaisley.helpers.ApiSportsHelper;
import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.LoggingHelper;
import com.jwpaisley.helpers.TimeHelper;
import com.jwpaisley.models.Sport;
import com.jwpaisley.models.SportsPredictionLeague;
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
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SportsPredictionLeaguesController {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public static SportsPredictionLeague sportsPredictionLeagueFromResultSet(ResultSet rs) throws SQLException {
        return new SportsPredictionLeague(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("image_url"),
            rs.getString("description"),
            rs.getString("sport") != null ? Sport.valueOf(rs.getString("sport")) : null,
            rs.getBoolean("is_private"),
            rs.getInt("first_place_prize_coins"),
            rs.getInt("second_place_prize_coins"),
            rs.getInt("third_place_prize_coins"),
            rs.getInt("api_sports_league_id"),
            rs.getInt("api_sports_season_id"),
            rs.getString("league_image_url"),
            TimeHelper.toUtcIsoString(rs.getTimestamp("league_start_date")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("league_end_date")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("created_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private static Timestamp toSqlTimestamp(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        String normalized = value.trim();
        if (normalized.length() == 10) {
            normalized += "T00:00:00Z";
        }

        try {
            return Timestamp.from(Instant.parse(normalized));
        } catch (DateTimeParseException e) {
            return Timestamp.valueOf(normalized.replace("Z", ""));
        }
    }

    private static SportsPredictionLeague hydrateLeagueWithApiSportsData(SportsPredictionLeague league) throws Exception {
        if (league == null || league.sport() == null || league.apiSportsLeagueId() <= 0 || league.apiSportsSeasonId() <= 0) {
            LoggingHelper.debug("Skipping API Sports hydration for league because required data is missing: {league=" + league + ", sport=" + (league != null ? league.sport() : null) + ", leagueId=" + (league != null ? league.apiSportsLeagueId() : null) + ", seasonId=" + (league != null ? league.apiSportsSeasonId() : null) + "}");
            return league;
        }

        String sportsPath = "/leagues?id=" + league.apiSportsLeagueId() + "&season=" + league.apiSportsSeasonId();
        HttpRequest.Builder requestBuilder = ApiSportsHelper.buildRequestForSport(league.sport(), sportsPath);
        HttpRequest request = requestBuilder.GET().build();

        LoggingHelper.debug("Preparing API Sports hydration request: {sport=" + league.sport() + ", url=" + request.uri() + ", headers={x-apisports-key-present=" + (ApiSportsHelper.getApiKey() != null && !ApiSportsHelper.getApiKey().isBlank()) + "}} ");

        HttpResponse<String> response;
        try {
            response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
            LoggingHelper.debug("API Sports hydration response: {status=" + response.statusCode() + ", body=" + response.body() + "}");
        } catch (Exception e) {
            LoggingHelper.error("API Sports hydration request threw an exception: " + e.getMessage());
            throw e;
        }

        if (response.statusCode() >= 400) {
            LoggingHelper.error("API Sports hydration failed with status " + response.statusCode() + " for: {sport=" + league.sport() + ", leagueId=" + league.apiSportsLeagueId() + ", seasonId=" + league.apiSportsSeasonId() + ", url=" + request.uri() + ", responseBody=" + response.body() + "}");
            throw new IllegalStateException("API Sports request failed with status " + response.statusCode());
        }

        JsonNode root = OBJECT_MAPPER.readTree(response.body());
        JsonNode responseItems = root.path("response");
        if (!responseItems.isArray() || responseItems.isEmpty()) {
            LoggingHelper.warning("API Sports hydration returned no response items: {sport=" + league.sport() + ", leagueId=" + league.apiSportsLeagueId() + ", seasonId=" + league.apiSportsSeasonId() + ", body=" + response.body() + "}");
            return league;
        }

        JsonNode firstItem = responseItems.get(0);
        JsonNode leagueNode = firstItem.path("league");
        JsonNode seasonNode = firstItem.path("seasons");
        JsonNode selectedSeason = null;

        if (seasonNode.isArray()) {
            for (JsonNode candidate : seasonNode) {
                if (candidate.path("season").asInt(0) == league.apiSportsSeasonId()) {
                    selectedSeason = candidate;
                    break;
                }
            }
            if (selectedSeason == null && !seasonNode.isEmpty()) {
                selectedSeason = seasonNode.get(0);
            }
        }

        String apiLeagueImageUrl = leagueNode.path("logo").asText(null);
        String apiLeagueStartDate = selectedSeason != null ? selectedSeason.path("start").asText(null) : null;
        String apiLeagueEndDate = selectedSeason != null ? selectedSeason.path("end").asText(null) : null;

        LoggingHelper.debug("Hydrated API Sports fields: {leagueName=" + league.name() + ", sport=" + league.sport() + ", leagueImageUrl=" + apiLeagueImageUrl + ", leagueStartDate=" + apiLeagueStartDate + ", leagueEndDate=" + apiLeagueEndDate + "}");

        return new SportsPredictionLeague(
            league.id(),
            league.name(),
            league.imageUrl(),
            league.description(),
            league.sport(),
            league.isPrivate(),
            league.firstPlacePrizeCoins(),
            league.secondPlacePrizeCoins(),
            league.thirdPlacePrizeCoins(),
            league.apiSportsLeagueId(),
            league.apiSportsSeasonId(),
            apiLeagueImageUrl != null ? apiLeagueImageUrl : league.leagueImageUrl(),
            apiLeagueStartDate != null ? apiLeagueStartDate : league.leagueStartDate(),
            apiLeagueEndDate != null ? apiLeagueEndDate : league.leagueEndDate(),
            league.createdAt(),
            league.updatedAt()
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sports prediction leagues");
    }

    private List<SportsPredictionLeague> fetchLeagues(String sql, Object... params) throws SQLException {
        List<SportsPredictionLeague> leagues = new ArrayList<>();
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < params.length; i++) {
                pstmt.setObject(i + 1, params[i]);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    leagues.add(sportsPredictionLeagueFromResultSet(rs));
                }
            }
        }

        return leagues;
    }

    public void getAll(Context ctx) {
        String sql = "SELECT * FROM sports_prediction_leagues ORDER BY created_at DESC";

        try {
            ctx.json(fetchLeagues(sql));
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getMyLeagues(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID userId = AuthHelper.getCurrentUserId(ctx);
        if (userId == null) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        String sql = """
            SELECT l.*
            FROM sports_prediction_leagues l
            INNER JOIN sports_prediction_league_participants p ON p.league_id = l.id
            WHERE p.user_id = ?::uuid
            ORDER BY l.created_at DESC
        """;

        try {
            ctx.json(fetchLeagues(sql, userId));
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getPastLeagues(Context ctx) {
        String sql = """
            SELECT *
            FROM sports_prediction_leagues
            WHERE is_private = false
              AND league_end_date IS NOT NULL
              AND league_end_date < CURRENT_TIMESTAMP
            ORDER BY league_end_date DESC
        """;

        try {
            ctx.json(fetchLeagues(sql));
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getOpenLeagues(Context ctx) {
        String sql = """
            SELECT *
            FROM sports_prediction_leagues
            WHERE is_private = false
              AND (league_end_date IS NULL OR league_end_date >= CURRENT_TIMESTAMP)
            ORDER BY league_start_date ASC NULLS LAST, league_end_date ASC NULLS LAST, created_at DESC
        """;

        try {
            ctx.json(fetchLeagues(sql));
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM sports_prediction_leagues WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionLeagueFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction league not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        SportsPredictionLeague newLeague = ctx.bodyAsClass(SportsPredictionLeague.class);
        SportsPredictionLeague hydratedLeague;
        try {
            hydratedLeague = hydrateLeagueWithApiSportsData(newLeague);
        } catch (Exception e) {
            handleError(ctx, e);
            return;
        }

        String sql = """
            INSERT INTO sports_prediction_leagues (
                name, image_url, description, sport, is_private,
                first_place_prize_coins, second_place_prize_coins, third_place_prize_coins,
                api_sports_league_id, api_sports_season_id, league_image_url,
                league_start_date, league_end_date
            ) VALUES (?, ?, ?, CAST(? AS sports_prediction_sport), ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, hydratedLeague.name());
            pstmt.setString(2, hydratedLeague.imageUrl());
            pstmt.setString(3, hydratedLeague.description());
            pstmt.setString(4, hydratedLeague.sport() != null ? hydratedLeague.sport().name() : null);
            pstmt.setBoolean(5, hydratedLeague.isPrivate());
            pstmt.setInt(6, hydratedLeague.firstPlacePrizeCoins());
            pstmt.setInt(7, hydratedLeague.secondPlacePrizeCoins());
            pstmt.setInt(8, hydratedLeague.thirdPlacePrizeCoins());
            pstmt.setInt(9, hydratedLeague.apiSportsLeagueId());
            pstmt.setInt(10, hydratedLeague.apiSportsSeasonId());
            pstmt.setString(11, hydratedLeague.leagueImageUrl());
            pstmt.setTimestamp(12, toSqlTimestamp(hydratedLeague.leagueStartDate()));
            pstmt.setTimestamp(13, toSqlTimestamp(hydratedLeague.leagueEndDate()));

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(sportsPredictionLeagueFromResultSet(rs));
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
        SportsPredictionLeague updatedLeague = ctx.bodyAsClass(SportsPredictionLeague.class);
        String sql = """
            UPDATE sports_prediction_leagues SET
                name = ?, image_url = ?, description = ?, sport = CAST(? AS sport), is_private = ?,
                first_place_prize_coins = ?, second_place_prize_coins = ?, third_place_prize_coins = ?,
                api_sports_league_id = ?, api_sports_season_id = ?, league_image_url = ?,
                league_start_date = ?, league_end_date = ?
            WHERE id = ?::uuid
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, updatedLeague.name());
            pstmt.setString(2, updatedLeague.imageUrl());
            pstmt.setString(3, updatedLeague.description());
            pstmt.setString(4, updatedLeague.sport() != null ? updatedLeague.sport().name() : null);
            pstmt.setBoolean(5, updatedLeague.isPrivate());
            pstmt.setInt(6, updatedLeague.firstPlacePrizeCoins());
            pstmt.setInt(7, updatedLeague.secondPlacePrizeCoins());
            pstmt.setInt(8, updatedLeague.thirdPlacePrizeCoins());
            pstmt.setInt(9, updatedLeague.apiSportsLeagueId());
            pstmt.setInt(10, updatedLeague.apiSportsSeasonId());
            pstmt.setString(11, updatedLeague.leagueImageUrl());
            pstmt.setTimestamp(12, toSqlTimestamp(updatedLeague.leagueStartDate()));
            pstmt.setTimestamp(13, toSqlTimestamp(updatedLeague.leagueEndDate()));
            pstmt.setObject(14, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionLeagueFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction league not found");
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
        String sql = "DELETE FROM sports_prediction_leagues WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Sports prediction league not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
