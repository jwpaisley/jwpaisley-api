package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.models.SportsPredictionFixture;
import com.jwpaisley.services.DatabaseService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SportsPredictionFixturesController {
    public static SportsPredictionFixture sportsPredictionFixtureFromResultSet(ResultSet rs) throws SQLException {
        return new SportsPredictionFixture(
            rs.getObject("id", UUID.class),
            rs.getInt("api_sports_fixture_id"),
            rs.getObject("league_id", UUID.class),
            rs.getObject("home_team_id", UUID.class),
            rs.getObject("away_team_id", UUID.class),
            rs.getTimestamp("commence_time") != null ? rs.getTimestamp("commence_time").toString() : null,
            rs.getObject("home_odds", Double.class),
            rs.getObject("away_odds", Double.class),
            rs.getObject("draw_odds", Double.class),
            rs.getString("status"),
            rs.getObject("home_score", Integer.class),
            rs.getObject("away_score", Integer.class),
            rs.getObject("winning_team_id", UUID.class),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sports prediction fixtures");
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

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        SportsPredictionFixture newFixture = ctx.bodyAsClass(SportsPredictionFixture.class);
        String sql = """
            INSERT INTO sports_prediction_fixtures (
                api_sports_fixture_id, league_id, home_team_id, away_team_id,
                commence_time, home_odds, away_odds, draw_odds, status,
                home_score, away_score, winning_team_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, newFixture.apiSportsFixtureId());
            pstmt.setObject(2, newFixture.leagueId());
            pstmt.setObject(3, newFixture.homeTeamId());
            pstmt.setObject(4, newFixture.awayTeamId());
            pstmt.setTimestamp(5, newFixture.commenceTime() != null ? java.sql.Timestamp.valueOf(newFixture.commenceTime().replace("Z", ".000000")) : null);
            pstmt.setObject(6, newFixture.homeOdds());
            pstmt.setObject(7, newFixture.awayOdds());
            pstmt.setObject(8, newFixture.drawOdds());
            pstmt.setString(9, newFixture.status());
            pstmt.setObject(10, newFixture.homeScore());
            pstmt.setObject(11, newFixture.awayScore());
            pstmt.setObject(12, newFixture.winningTeamId());

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
                api_sports_fixture_id = ?, league_id = ?, home_team_id = ?, away_team_id = ?,
                commence_time = ?, home_odds = ?, away_odds = ?, draw_odds = ?, status = ?,
                home_score = ?, away_score = ?, winning_team_id = ?
            WHERE id = ?::uuid
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, updatedFixture.apiSportsFixtureId());
            pstmt.setObject(2, updatedFixture.leagueId());
            pstmt.setObject(3, updatedFixture.homeTeamId());
            pstmt.setObject(4, updatedFixture.awayTeamId());
            pstmt.setTimestamp(5, updatedFixture.commenceTime() != null ? java.sql.Timestamp.valueOf(updatedFixture.commenceTime().replace("Z", ".000000")) : null);
            pstmt.setObject(6, updatedFixture.homeOdds());
            pstmt.setObject(7, updatedFixture.awayOdds());
            pstmt.setObject(8, updatedFixture.drawOdds());
            pstmt.setString(9, updatedFixture.status());
            pstmt.setObject(10, updatedFixture.homeScore());
            pstmt.setObject(11, updatedFixture.awayScore());
            pstmt.setObject(12, updatedFixture.winningTeamId());
            pstmt.setObject(13, id);

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
