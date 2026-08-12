package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.models.SportsPredictionPick;
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

public class SportsPredictionPicksController {
    public static SportsPredictionPick sportsPredictionPickFromResultSet(ResultSet rs) throws SQLException {
        return new SportsPredictionPick(
            rs.getObject("id", UUID.class),
            rs.getObject("league_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("fixture_id", UUID.class),
            rs.getObject("selected_team_id", UUID.class),
            rs.getBoolean("is_draw_pick"),
            rs.getDouble("payout_multiplier"),
            rs.getString("status"),
            rs.getInt("coins_awarded"),
            rs.getInt("points_awarded"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sports prediction picks");
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
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        SportsPredictionPick newPick = ctx.bodyAsClass(SportsPredictionPick.class);
        String sql = """
            INSERT INTO sports_prediction_picks (
                league_id, user_id, fixture_id, selected_team_id, is_draw_pick,
                payout_multiplier, status, coins_awarded, points_awarded
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, newPick.leagueId());
            pstmt.setObject(2, newPick.userId());
            pstmt.setObject(3, newPick.fixtureId());
            pstmt.setObject(4, newPick.selectedTeamId());
            pstmt.setBoolean(5, newPick.isDrawPick());
            pstmt.setDouble(6, newPick.payoutMultiplier());
            pstmt.setString(7, newPick.status());
            pstmt.setInt(8, newPick.coinsAwarded());
            pstmt.setInt(9, newPick.pointsAwarded());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(sportsPredictionPickFromResultSet(rs));
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
        SportsPredictionPick updatedPick = ctx.bodyAsClass(SportsPredictionPick.class);
        String sql = """
            UPDATE sports_prediction_picks SET
                league_id = ?, user_id = ?, fixture_id = ?, selected_team_id = ?, is_draw_pick = ?,
                payout_multiplier = ?, status = ?, coins_awarded = ?, points_awarded = ?
            WHERE id = ?::uuid
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, updatedPick.leagueId());
            pstmt.setObject(2, updatedPick.userId());
            pstmt.setObject(3, updatedPick.fixtureId());
            pstmt.setObject(4, updatedPick.selectedTeamId());
            pstmt.setBoolean(5, updatedPick.isDrawPick());
            pstmt.setDouble(6, updatedPick.payoutMultiplier());
            pstmt.setString(7, updatedPick.status());
            pstmt.setInt(8, updatedPick.coinsAwarded());
            pstmt.setInt(9, updatedPick.pointsAwarded());
            pstmt.setObject(10, id);

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
