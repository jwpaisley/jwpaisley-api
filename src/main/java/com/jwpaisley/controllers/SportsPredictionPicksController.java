package com.jwpaisley.controllers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jwpaisley.helpers.ApiSportsHelper;
import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.LoggingHelper;
import com.jwpaisley.helpers.TimeHelper;
import com.jwpaisley.models.Sport;
import com.jwpaisley.models.SportsPredictionPick;
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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class SportsPredictionPicksController {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private record PendingFixtureSettlement(
        UUID fixtureId,
        int apiSportsFixtureId,
        UUID homeTeamId,
        UUID awayTeamId,
        Sport sport
    ) {}

    public static SportsPredictionPick sportsPredictionPickFromResultSet(ResultSet rs) throws SQLException {
        return new SportsPredictionPick(
            rs.getObject("id", UUID.class),
            rs.getObject("league_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("fixture_id", UUID.class),
            rs.getObject("selected_team_id", UUID.class),
            rs.getBoolean("is_draw_pick"),
            rs.getBoolean("is_correct"),
            rs.getBoolean("is_settled"),
            rs.getDouble("payout_multiplier"),
            rs.getString("status"),
            rs.getInt("coins_awarded"),
            rs.getInt("points_awarded"),
            TimeHelper.toUtcIsoString(rs.getTimestamp("created_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sports prediction picks");
    }

    private UUID resolveCurrentUserId(Context ctx) throws SQLException {
        UUID currentUserId = AuthHelper.getCurrentUserId(ctx);
        if (currentUserId != null) {
            return currentUserId;
        }

        String currentUserEmail = AuthHelper.getCurrentUserEmail(ctx);
        if (currentUserEmail == null || currentUserEmail.isBlank()) {
            return null;
        }

        String sql = "SELECT id FROM users WHERE email_address = ? LIMIT 1";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, currentUserEmail);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getObject("id", UUID.class);
                }
            }
        }

        return null;
    }

    private JsonNode fetchJsonFromApiSport(Sport sport, String path) throws Exception {
        HttpRequest request = ApiSportsHelper.buildRequestForSport(sport, path).GET().build();
        HttpResponse<String> response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 400) {
            throw new IllegalStateException("API Sports request failed with status " + response.statusCode() + " for path: " + path);
        }

        return OBJECT_MAPPER.readTree(response.body());
    }

    private String normalizeFixtureStatus(String rawStatus) {
        if (rawStatus == null || rawStatus.isBlank()) {
            return "SCHEDULED";
        }

        String normalized = rawStatus.trim().replace(' ', '_').toUpperCase();
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

    private boolean isFixtureLockedForPicks(UUID fixtureId) throws SQLException {
        String sql = """
            SELECT
                status,
                commence_time,
                (
                    status IN ('IN_PROGRESS', 'FINISHED')
                    OR (commence_time IS NOT NULL AND commence_time <= CURRENT_TIMESTAMP)
                ) AS is_locked
            FROM sports_prediction_fixtures
            WHERE id = ?::uuid
            LIMIT 1
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, fixtureId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundResponse("Sports prediction fixture not found");
                }

                return rs.getBoolean("is_locked");
            }
        }
    }

    private List<PendingFixtureSettlement> fetchPendingFixtureSettlements() throws SQLException {
        String sql = """
            SELECT DISTINCT
                f.id AS fixture_id,
                f.api_sports_fixture_id,
                f.home_team_id,
                f.away_team_id,
                l.sport
            FROM sports_prediction_picks p
            INNER JOIN sports_prediction_fixtures f ON f.id = p.fixture_id
            INNER JOIN sports_prediction_leagues l ON l.id = p.league_id
            WHERE p.is_settled = FALSE
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();
        List<PendingFixtureSettlement> pending = new ArrayList<>();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                pending.add(new PendingFixtureSettlement(
                    rs.getObject("fixture_id", UUID.class),
                    rs.getInt("api_sports_fixture_id"),
                    rs.getObject("home_team_id", UUID.class),
                    rs.getObject("away_team_id", UUID.class),
                    Sport.valueOf(rs.getString("sport"))
                ));
            }
        }

        return pending;
    }

    private int settleFixturePicks(PendingFixtureSettlement pendingFixture) throws Exception {
        JsonNode fixtureRoot = fetchJsonFromApiSport(pendingFixture.sport(), "/fixtures?id=" + pendingFixture.apiSportsFixtureId());
        JsonNode responseNode = fixtureRoot.path("response");

        if (!responseNode.isArray() || responseNode.isEmpty()) {
            return 0;
        }

        JsonNode fixtureNode = responseNode.get(0);
        String status = normalizeFixtureStatus(fixtureNode.path("fixture").path("status").path("long").asText(null));
        Integer homeScore = fixtureNode.path("goals").path("home").isNull() ? null : fixtureNode.path("goals").path("home").asInt();
        Integer awayScore = fixtureNode.path("goals").path("away").isNull() ? null : fixtureNode.path("goals").path("away").asInt();

        UUID winningTeamId = null;
        if (homeScore != null && awayScore != null) {
            if (homeScore > awayScore) {
                winningTeamId = pendingFixture.homeTeamId();
            } else if (awayScore > homeScore) {
                winningTeamId = pendingFixture.awayTeamId();
            }
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);

            String updateFixtureSql = """
                UPDATE sports_prediction_fixtures
                SET
                    status = ?,
                    home_score = ?,
                    away_score = ?,
                    winning_team_id = ?
                WHERE id = ?::uuid
            """;

            try (PreparedStatement fixtureStmt = conn.prepareStatement(updateFixtureSql)) {
                fixtureStmt.setString(1, status);
                fixtureStmt.setObject(2, homeScore);
                fixtureStmt.setObject(3, awayScore);
                fixtureStmt.setObject(4, winningTeamId);
                fixtureStmt.setObject(5, pendingFixture.fixtureId());
                fixtureStmt.executeUpdate();
            }

            if (!"FINISHED".equals(status) || homeScore == null || awayScore == null) {
                conn.commit();
                return 0;
            }

            String settlePicksSql = """
                WITH settled AS (
                    UPDATE sports_prediction_picks p
                    SET
                        is_correct = (
                            CASE
                                WHEN ? THEN p.is_draw_pick = TRUE
                                ELSE p.is_draw_pick = FALSE AND p.selected_team_id = ?::uuid
                            END
                        ),
                        is_settled = TRUE,
                        points_awarded = (
                            CASE
                                WHEN (
                                    CASE
                                        WHEN ? THEN p.is_draw_pick = TRUE
                                        ELSE p.is_draw_pick = FALSE AND p.selected_team_id = ?::uuid
                                    END
                                )
                                THEN CEIL(10 * p.payout_multiplier)::int
                                ELSE 0
                            END
                        ),
                        coins_awarded = (
                            CASE
                                WHEN (
                                    CASE
                                        WHEN ? THEN p.is_draw_pick = TRUE
                                        ELSE p.is_draw_pick = FALSE AND p.selected_team_id = ?::uuid
                                    END
                                )
                                THEN CEIL(10 * p.payout_multiplier)::int
                                ELSE 0
                            END
                        ),
                        status = (
                            CASE
                                WHEN (
                                    CASE
                                        WHEN ? THEN p.is_draw_pick = TRUE
                                        ELSE p.is_draw_pick = FALSE AND p.selected_team_id = ?::uuid
                                    END
                                )
                                THEN 'won'
                                ELSE 'lost'
                            END
                        )
                    WHERE p.fixture_id = ?::uuid
                      AND p.is_settled = FALSE
                    RETURNING p.league_id, p.user_id, points_awarded, coins_awarded
                ),
                participant_totals AS (
                    SELECT league_id, user_id, SUM(points_awarded)::int AS points_total
                    FROM settled
                    GROUP BY league_id, user_id
                ),
                user_totals AS (
                    SELECT user_id, SUM(coins_awarded)::int AS coins_total
                    FROM settled
                    GROUP BY user_id
                ),
                update_participants AS (
                    UPDATE sports_prediction_league_participants lp
                    SET points = lp.points + pt.points_total
                    FROM participant_totals pt
                    WHERE lp.league_id = pt.league_id
                      AND lp.user_id = pt.user_id
                    RETURNING lp.id
                ),
                update_users AS (
                    UPDATE users u
                    SET coins = u.coins + ut.coins_total
                    FROM user_totals ut
                    WHERE u.id = ut.user_id
                    RETURNING u.id
                )
                SELECT COUNT(*) AS settled_count
                FROM settled
            """;

            int settledCount = 0;
            boolean isDrawResult = homeScore.equals(awayScore);

            try (PreparedStatement settleStmt = conn.prepareStatement(settlePicksSql)) {
                settleStmt.setBoolean(1, isDrawResult);
                settleStmt.setObject(2, winningTeamId);
                settleStmt.setBoolean(3, isDrawResult);
                settleStmt.setObject(4, winningTeamId);
                settleStmt.setBoolean(5, isDrawResult);
                settleStmt.setObject(6, winningTeamId);
                settleStmt.setBoolean(7, isDrawResult);
                settleStmt.setObject(8, winningTeamId);
                settleStmt.setObject(9, pendingFixture.fixtureId());

                try (ResultSet rs = settleStmt.executeQuery()) {
                    if (rs.next()) {
                        settledCount = rs.getInt("settled_count");
                    }
                }
            }

            conn.commit();
            return settledCount;
        }
    }

    public void getAll(Context ctx) {
        List<SportsPredictionPick> picks = new ArrayList<>();
        String sql = "SELECT * FROM sports_prediction_picks ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                picks.add(sportsPredictionPickFromResultSet(rs));
            }
            ctx.json(picks);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM sports_prediction_picks WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionPickFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction pick not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        String sql = """
            INSERT INTO sports_prediction_picks (
                league_id, user_id, fixture_id, selected_team_id, is_draw_pick,
                is_correct, is_settled, payout_multiplier, status, coins_awarded, points_awarded
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT (league_id, user_id, fixture_id)
            DO UPDATE SET
                selected_team_id = EXCLUDED.selected_team_id,
                is_draw_pick = EXCLUDED.is_draw_pick,
                is_correct = EXCLUDED.is_correct,
                is_settled = EXCLUDED.is_settled,
                payout_multiplier = EXCLUDED.payout_multiplier,
                status = EXCLUDED.status,
                coins_awarded = EXCLUDED.coins_awarded,
                points_awarded = EXCLUDED.points_awarded
            RETURNING *, (xmax = 0) AS inserted;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            UUID currentUserId = resolveCurrentUserId(ctx);
            SportsPredictionPick newPick = ctx.bodyAsClass(SportsPredictionPick.class);

            if (!AuthHelper.isAdmin(ctx) && (currentUserId == null || !currentUserId.equals(newPick.userId()))) {
                ctx.status(403).result("Forbidden");
                return;
            }

            if (isFixtureLockedForPicks(newPick.fixtureId())) {
                ctx.status(409).result("Pick cannot be created or updated after kickoff");
                return;
            }

            pstmt.setObject(1, newPick.leagueId());
            pstmt.setObject(2, newPick.userId());
            pstmt.setObject(3, newPick.fixtureId());
            pstmt.setObject(4, newPick.selectedTeamId());
            pstmt.setBoolean(5, newPick.isDrawPick());
            pstmt.setBoolean(6, newPick.isCorrect());
            pstmt.setBoolean(7, newPick.isSettled());
            pstmt.setDouble(8, newPick.payoutMultiplier());
            pstmt.setString(9, newPick.status());
            pstmt.setInt(10, newPick.coinsAwarded());
            pstmt.setInt(11, newPick.pointsAwarded());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    boolean inserted = rs.getBoolean("inserted");
                    ctx.status(inserted ? 201 : 200).json(sportsPredictionPickFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void settleJob(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !(AuthHelper.isServiceAccount(ctx) || AuthHelper.isAdmin(ctx))) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        CompletableFuture.runAsync(() -> {
            try {
                List<PendingFixtureSettlement> pendingFixtures = fetchPendingFixtureSettlements();
                LoggingHelper.info("Starting pick settlement job for " + pendingFixtures.size() + " fixture(s) with unsettled picks");

                int settledPickCount = 0;
                Set<UUID> settledFixtureIds = new HashSet<>();

                for (PendingFixtureSettlement pendingFixture : pendingFixtures) {
                    int settledCount = settleFixturePicks(pendingFixture);
                    if (settledCount > 0) {
                        settledFixtureIds.add(pendingFixture.fixtureId());
                        settledPickCount += settledCount;
                    }
                }

                LoggingHelper.success("Settled " + settledPickCount + " pick(s) across " + settledFixtureIds.size() + " finished fixture(s)");
            } catch (Exception e) {
                LoggingHelper.error("Pick settlement job failed: " + e.getMessage());
            }
        });

        ctx.status(200).result("Pick settlement job started");
    }

    public void getMyPicksForLeague(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        try {
            UUID leagueId = UUID.fromString(ctx.pathParam("leagueId"));
            UUID currentUserId = resolveCurrentUserId(ctx);

            if (currentUserId == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            List<SportsPredictionPick> picks = new ArrayList<>();
            String sql = """
                SELECT *
                FROM sports_prediction_picks
                WHERE league_id = ?::uuid
                  AND user_id = ?::uuid
                ORDER BY created_at DESC
            """;
            DataSource ds = DatabaseService.getInstance().getDataSource();

            try (Connection conn = ds.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setObject(1, leagueId);
                pstmt.setObject(2, currentUserId);

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        picks.add(sportsPredictionPickFromResultSet(rs));
                    }
                }
            }

            ctx.json(picks);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getMyPickForLeagueFixture(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        try {
            UUID leagueId = UUID.fromString(ctx.pathParam("leagueId"));
            UUID fixtureId = UUID.fromString(ctx.pathParam("fixtureId"));
            UUID currentUserId = resolveCurrentUserId(ctx);

            if (currentUserId == null) {
                ctx.status(401).result("Unauthorized");
                return;
            }

            String sql = """
                SELECT *
                FROM sports_prediction_picks
                WHERE league_id = ?::uuid
                  AND fixture_id = ?::uuid
                  AND user_id = ?::uuid
                LIMIT 1
            """;
            DataSource ds = DatabaseService.getInstance().getDataSource();

            try (Connection conn = ds.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {

                pstmt.setObject(1, leagueId);
                pstmt.setObject(2, fixtureId);
                pstmt.setObject(3, currentUserId);

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        ctx.json(sportsPredictionPickFromResultSet(rs));
                        return;
                    }
                }
            }

            ctx.status(204);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getRecentForLeague(Context ctx) {
        UUID leagueId = UUID.fromString(ctx.pathParam("leagueId"));
        int page = 0;
        int pageSize = 20;

        try {
            String pageParam = ctx.queryParam("page");
            if (pageParam != null) {
                page = Math.max(Integer.parseInt(pageParam), 0);
            }
        } catch (NumberFormatException ignored) {
            page = 0;
        }

        try {
            String pageSizeParam = ctx.queryParam("pageSize");
            if (pageSizeParam != null) {
                pageSize = Math.max(Integer.parseInt(pageSizeParam), 1);
            }
        } catch (NumberFormatException ignored) {
            pageSize = 20;
        }

        int offset = page * pageSize;

        List<SportsPredictionPick> picks = new ArrayList<>();
        String sql = """
            SELECT *
            FROM sports_prediction_picks
            WHERE league_id = ?::uuid
            ORDER BY created_at DESC
            LIMIT ?
            OFFSET ?
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, leagueId);
            pstmt.setInt(2, pageSize + 1);
            pstmt.setInt(3, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    picks.add(sportsPredictionPickFromResultSet(rs));
                }
            }

            boolean hasMore = picks.size() > pageSize;
            if (hasMore) {
                picks.remove(picks.size() - 1);
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("page", page);
            result.put("pageSize", pageSize);
            result.put("hasMore", hasMore);
            result.put("picks", picks);
            ctx.json(result);
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
        SportsPredictionPick updatedPick = ctx.bodyAsClass(SportsPredictionPick.class);
        String sql = """
            UPDATE sports_prediction_picks SET
                league_id = ?, user_id = ?, fixture_id = ?, selected_team_id = ?, is_draw_pick = ?,
                is_correct = ?, is_settled = ?, payout_multiplier = ?, status = ?, coins_awarded = ?, points_awarded = ?
            WHERE id = ?::uuid
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            if (isFixtureLockedForPicks(updatedPick.fixtureId())) {
                ctx.status(409).result("Pick cannot be updated after kickoff");
                return;
            }

            pstmt.setObject(1, updatedPick.leagueId());
            pstmt.setObject(2, updatedPick.userId());
            pstmt.setObject(3, updatedPick.fixtureId());
            pstmt.setObject(4, updatedPick.selectedTeamId());
            pstmt.setBoolean(5, updatedPick.isDrawPick());
            pstmt.setBoolean(6, updatedPick.isCorrect());
            pstmt.setBoolean(7, updatedPick.isSettled());
            pstmt.setDouble(8, updatedPick.payoutMultiplier());
            pstmt.setString(9, updatedPick.status());
            pstmt.setInt(10, updatedPick.coinsAwarded());
            pstmt.setInt(11, updatedPick.pointsAwarded());
            pstmt.setObject(12, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionPickFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction pick not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getLeagueUserTotals(Context ctx) {
        UUID leagueId = UUID.fromString(ctx.pathParam("leagueId"));
        String sql = """
            SELECT user_id, SUM(points_awarded) AS total_points
            FROM sports_prediction_picks
            WHERE league_id = ?::uuid
              AND is_settled = TRUE
              AND is_correct = TRUE
            GROUP BY user_id
            ORDER BY total_points DESC
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();
        List<Map<String, Object>> totals = new ArrayList<>();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, leagueId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("userId", rs.getObject("user_id", UUID.class));
                    row.put("totalPoints", rs.getInt("total_points"));
                    totals.add(row);
                }
            }

            ctx.json(totals);
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
        String sql = "DELETE FROM sports_prediction_picks WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Sports prediction pick not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
