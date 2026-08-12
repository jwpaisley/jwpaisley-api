package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.models.SportsPredictionLeagueParticipant;
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

public class SportsPredictionLeagueParticipantsController {
    public static SportsPredictionLeagueParticipant sportsPredictionLeagueParticipantFromResultSet(ResultSet rs) throws SQLException {
        return new SportsPredictionLeagueParticipant(
            rs.getObject("id", UUID.class),
            rs.getObject("league_id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getInt("points"),
            rs.getTimestamp("joined_at") != null ? rs.getTimestamp("joined_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sports prediction league participants");
    }

    public void getAll(Context ctx) {
        List<SportsPredictionLeagueParticipant> participants = new ArrayList<>();
        String sql = "SELECT * FROM sports_prediction_league_participants ORDER BY joined_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                participants.add(sportsPredictionLeagueParticipantFromResultSet(rs));
            }
            ctx.json(participants);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM sports_prediction_league_participants WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionLeagueParticipantFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction league participant not found");
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

        SportsPredictionLeagueParticipant newParticipant = ctx.bodyAsClass(SportsPredictionLeagueParticipant.class);
        String sql = "INSERT INTO sports_prediction_league_participants (league_id, user_id, points) VALUES (?, ?, ?) RETURNING *";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, newParticipant.leagueId());
            pstmt.setObject(2, newParticipant.userId());
            pstmt.setInt(3, newParticipant.points());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(sportsPredictionLeagueParticipantFromResultSet(rs));
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
        SportsPredictionLeagueParticipant updatedParticipant = ctx.bodyAsClass(SportsPredictionLeagueParticipant.class);
        String sql = "UPDATE sports_prediction_league_participants SET league_id = ?, user_id = ?, points = ? WHERE id = ?::uuid RETURNING *";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, updatedParticipant.leagueId());
            pstmt.setObject(2, updatedParticipant.userId());
            pstmt.setInt(3, updatedParticipant.points());
            pstmt.setObject(4, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionLeagueParticipantFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction league participant not found");
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
        String sql = "DELETE FROM sports_prediction_league_participants WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Sports prediction league participant not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
