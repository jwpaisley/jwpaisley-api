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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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

    private List<Map<String, Object>> fetchParticipantRows(String sql, Object... params) throws SQLException {
        List<Map<String, Object>> participants = new ArrayList<>();
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                pstmt.setObject(i + 1, params[i]);
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> participant = new LinkedHashMap<>();
                    UUID participantId = rs.getObject("id", UUID.class);
                    UUID leagueId = rs.getObject("league_id", UUID.class);
                    UUID userId = rs.getObject("user_id", UUID.class);
                    String firstName = rs.getString("first_name");
                    String lastName = rs.getString("last_name");
                    String profilePictureUrl = rs.getString("profile_picture_url");

                    participant.put("id", participantId);
                    participant.put("leagueId", leagueId);
                    participant.put("userId", userId);
                    participant.put("points", rs.getInt("points"));
                    participant.put("joinedAt", rs.getTimestamp("joined_at") != null ? rs.getTimestamp("joined_at").toString() : null);
                    participant.put("firstName", firstName);
                    participant.put("lastName", lastName);
                    participant.put("profilePictureUrl", profilePictureUrl);
                    participant.put("profilePictureId", profilePictureUrl);
                    participants.add(participant);
                }
            }
        }

        return participants;
    }

    public void getAll(Context ctx) {
        String sql = """
            SELECT p.*, u.first_name AS first_name, u.last_name AS last_name, u.profile_picture_url AS profile_picture_url
            FROM sports_prediction_league_participants p
            LEFT JOIN users u ON u.id = p.user_id
            ORDER BY p.joined_at DESC
        """;

        try {
            ctx.json(fetchParticipantRows(sql));
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = """
            SELECT p.*, u.first_name AS first_name, u.last_name AS last_name, u.profile_picture_url AS profile_picture_url
            FROM sports_prediction_league_participants p
            LEFT JOIN users u ON u.id = p.user_id
            WHERE p.id = ?::uuid
        """;

        try {
            List<Map<String, Object>> participants = fetchParticipantRows(sql, id);
            if (participants.isEmpty()) {
                throw new NotFoundResponse("Sports prediction league participant not found");
            }
            ctx.json(participants.getFirst());
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getParticipantsForLeague(Context ctx) {
        UUID leagueId = UUID.fromString(ctx.pathParam("leagueId"));
        String sql = """
            SELECT p.*, u.first_name AS first_name, u.last_name AS last_name, u.profile_picture_url AS profile_picture_url
            FROM sports_prediction_league_participants p
            LEFT JOIN users u ON u.id = p.user_id
            WHERE p.league_id = ?::uuid
            ORDER BY p.points DESC, p.joined_at ASC
        """;

        try {
            ctx.json(fetchParticipantRows(sql, leagueId));
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID currentUserId = AuthHelper.getCurrentUserId(ctx);
        SportsPredictionLeagueParticipant newParticipant = ctx.bodyAsClass(SportsPredictionLeagueParticipant.class);

        if (!AuthHelper.isAdmin(ctx) && (currentUserId == null || !currentUserId.equals(newParticipant.userId()))) {
            ctx.status(403).result("Forbidden");
            return;
        }

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
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        UUID currentUserId = AuthHelper.getCurrentUserId(ctx);
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            String fetchSql = "SELECT id, league_id, user_id, points, joined_at FROM sports_prediction_league_participants WHERE id = ?::uuid";
            UUID leagueId = null;
            UUID userId = null;

            try (PreparedStatement fetchStmt = conn.prepareStatement(fetchSql)) {
                fetchStmt.setObject(1, id);
                try (ResultSet rs = fetchStmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new NotFoundResponse("Sports prediction league participant not found");
                    }
                    leagueId = rs.getObject("league_id", UUID.class);
                    userId = rs.getObject("user_id", UUID.class);
                }
            }

            if (!AuthHelper.isAdmin(ctx) && (currentUserId == null || !currentUserId.equals(userId))) {
                ctx.status(403).result("Forbidden");
                return;
            }

            conn.setAutoCommit(false);

            String deletePicksSql = "DELETE FROM sports_prediction_picks WHERE league_id = ?::uuid AND user_id = ?::uuid";
            try (PreparedStatement deletePicksStmt = conn.prepareStatement(deletePicksSql)) {
                deletePicksStmt.setObject(1, leagueId);
                deletePicksStmt.setObject(2, userId);
                deletePicksStmt.executeUpdate();
            }

            String deleteParticipantSql = "DELETE FROM sports_prediction_league_participants WHERE id = ?::uuid";
            try (PreparedStatement deleteParticipantStmt = conn.prepareStatement(deleteParticipantSql)) {
                deleteParticipantStmt.setObject(1, id);
                int rowsDeleted = deleteParticipantStmt.executeUpdate();

                if (rowsDeleted <= 0) {
                    throw new NotFoundResponse("Sports prediction league participant not found");
                }
            }

            conn.commit();
            ctx.status(204);
        } catch (SQLException e) {
            handleError(ctx, e);
        } catch (NotFoundResponse e) {
            throw e;
        }
    }
}
