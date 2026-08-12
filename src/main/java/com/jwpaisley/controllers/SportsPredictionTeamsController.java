package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.models.SportsPredictionTeam;
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

public class SportsPredictionTeamsController {
    public static SportsPredictionTeam sportsPredictionTeamFromResultSet(ResultSet rs) throws SQLException {
        return new SportsPredictionTeam(
            rs.getObject("id", UUID.class),
            rs.getInt("api_sports_team_id"),
            rs.getString("name"),
            rs.getString("code"),
            rs.getString("logo_url"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sports prediction teams");
    }

    public void getAll(Context ctx) {
        List<SportsPredictionTeam> teams = new ArrayList<>();
        String sql = "SELECT * FROM sports_prediction_teams ORDER BY name ASC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                teams.add(sportsPredictionTeamFromResultSet(rs));
            }
            ctx.json(teams);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM sports_prediction_teams WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionTeamFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction team not found");
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

        SportsPredictionTeam newTeam = ctx.bodyAsClass(SportsPredictionTeam.class);
        String sql = "INSERT INTO sports_prediction_teams (api_sports_team_id, name, code, logo_url) VALUES (?, ?, ?, ?) RETURNING *";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, newTeam.apiSportsTeamId());
            pstmt.setString(2, newTeam.name());
            pstmt.setString(3, newTeam.code());
            pstmt.setString(4, newTeam.logoUrl());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(sportsPredictionTeamFromResultSet(rs));
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
        SportsPredictionTeam updatedTeam = ctx.bodyAsClass(SportsPredictionTeam.class);
        String sql = "UPDATE sports_prediction_teams SET api_sports_team_id = ?, name = ?, code = ?, logo_url = ? WHERE id = ?::uuid RETURNING *";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, updatedTeam.apiSportsTeamId());
            pstmt.setString(2, updatedTeam.name());
            pstmt.setString(3, updatedTeam.code());
            pstmt.setString(4, updatedTeam.logoUrl());
            pstmt.setObject(5, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sportsPredictionTeamFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sports prediction team not found");
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
        String sql = "DELETE FROM sports_prediction_teams WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Sports prediction team not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
