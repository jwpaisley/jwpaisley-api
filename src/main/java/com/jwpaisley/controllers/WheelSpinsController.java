package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.models.WheelOption;
import com.jwpaisley.models.WheelSpin;
import com.jwpaisley.services.DatabaseService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

public class WheelSpinsController {
    public static WheelSpin wheelSpinFromResultSet(ResultSet rs) throws SQLException {
        return new WheelSpin(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getInt("outcome"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing wheel spins");
    }

    public void getAll(Context ctx) {
        List<WheelSpin> wheelSpins = new ArrayList<>();
        String sql = "SELECT * FROM public.wheel_spins ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                wheelSpins.add(wheelSpinFromResultSet(rs));
            }
            ctx.json(wheelSpins);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM public.wheel_spins WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(wheelSpinFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Wheel spin not found");
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

        WheelSpin newSpin = ctx.bodyAsClass(WheelSpin.class);
        String sql = "INSERT INTO public.wheel_spins (user_id, outcome) VALUES (?::uuid, ?) RETURNING *;";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, newSpin.userId());
            pstmt.setInt(2, newSpin.outcome());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(wheelSpinFromResultSet(rs));
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
        WheelSpin updatedSpin = ctx.bodyAsClass(WheelSpin.class);
        String sql = "UPDATE public.wheel_spins SET user_id = ?::uuid, outcome = ? WHERE id = ?::uuid RETURNING *;";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, updatedSpin.userId());
            pstmt.setInt(2, updatedSpin.outcome());
            pstmt.setObject(3, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(200).json(wheelSpinFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Wheel spin not found");
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
        String sql = "DELETE FROM public.wheel_spins WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Wheel spin not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void canSpinWheel(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID userId = AuthHelper.getCurrentUserId(ctx);
        if (userId == null) {
            ctx.status(400).result("Invalid user context");
            return;
        }

        boolean admin = AuthHelper.isAdmin(ctx);
        String lastSpinAt = getLatestSpinTimestamp(userId);
        boolean canSpin = admin || lastSpinAt == null || isOlderThanOneDay(lastSpinAt);

        Map<String, Object> response = new HashMap<>();
        response.put("canSpin", canSpin);
        response.put("lastSpinAt", lastSpinAt);
        ctx.json(response);
    }

    public void spinWheel(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID userId = AuthHelper.getCurrentUserId(ctx);
        if (userId == null) {
            ctx.status(400).result("Invalid user context");
            return;
        }

        if (!AuthHelper.isAdmin(ctx) && !isUserEligibleToSpin(userId)) {
            ctx.status(403).json(Map.of(
                "canSpin", false,
                "message", "User has spun within the last 24 hours"
            ));
            return;
        }

        List<WheelOption> options = getConfiguredWheelOptions();
        if (options.isEmpty()) {
            ctx.status(500).result("No wheel options configured");
            return;
        }

        WheelOption chosenOption = pickWeightedOption(options);
        int outcome = chosenOption.value();
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);

            WheelSpin createdSpin;
            int newCoinBalance;

            try (PreparedStatement insertSpin = conn.prepareStatement(
                "INSERT INTO public.wheel_spins (user_id, outcome) VALUES (?::uuid, ?) RETURNING *;")) {
                insertSpin.setObject(1, userId);
                insertSpin.setInt(2, outcome);

                try (ResultSet rs = insertSpin.executeQuery()) {
                    if (rs.next()) {
                        createdSpin = wheelSpinFromResultSet(rs);
                    } else {
                        throw new SQLException("Failed to create wheel spin record");
                    }
                }
            }

            String currentBalanceSql = "SELECT coins FROM users WHERE id = ?::uuid";
            try (PreparedStatement currentBalanceStmt = conn.prepareStatement(currentBalanceSql)) {
                currentBalanceStmt.setObject(1, userId);
                try (ResultSet rs = currentBalanceStmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new NotFoundResponse("User not found");
                    }
                    newCoinBalance = rs.getInt("coins") + outcome;
                }
            }

            String updateBalanceSql = "UPDATE users SET coins = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?::uuid";
            try (PreparedStatement updateBalanceStmt = conn.prepareStatement(updateBalanceSql)) {
                updateBalanceStmt.setInt(1, newCoinBalance);
                updateBalanceStmt.setObject(2, userId);
                updateBalanceStmt.executeUpdate();
            }

            conn.commit();

            Map<String, Object> response = new HashMap<>();
            response.put("spin", createdSpin);
            response.put("outcome", outcome);
            response.put("coinsAwarded", outcome);
            response.put("newCoinBalance", newCoinBalance);
            response.put("wheelOption", chosenOption);
            ctx.status(201).json(response);
        } catch (Exception e) {
            try {
                DataSource ds2 = DatabaseService.getInstance().getDataSource();
                try (Connection conn = ds2.getConnection()) {
                    conn.rollback();
                }
            } catch (SQLException ignored) {
                // no-op
            }
            handleError(ctx, e);
        }
    }

    private List<WheelOption> getConfiguredWheelOptions() {
        String sql = "SELECT * FROM public.wheel_options ORDER BY value ASC";
        List<WheelOption> options = new ArrayList<>();
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                options.add(new WheelOption(
                    rs.getObject("id", UUID.class),
                    rs.getInt("value"),
                    rs.getBigDecimal("probability"),
                    rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
                    rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
                ));
            }
        } catch (SQLException e) {
            System.err.println("Database Error: " + e.getMessage());
        }

        return options;
    }

    private WheelOption pickWeightedOption(List<WheelOption> options) {
        double roll = ThreadLocalRandom.current().nextDouble();
        double cumulative = 0.0;

        for (WheelOption option : options) {
            cumulative += option.probability().doubleValue();
            if (roll <= cumulative) {
                return option;
            }
        }

        return options.get(options.size() - 1);
    }

    private boolean isUserEligibleToSpin(UUID userId) {
        String lastSpinAt = getLatestSpinTimestamp(userId);
        return lastSpinAt == null || isOlderThanOneDay(lastSpinAt);
    }

    private String getLatestSpinTimestamp(UUID userId) {
        String sql = "SELECT created_at FROM public.wheel_spins WHERE user_id = ?::uuid ORDER BY created_at DESC LIMIT 1";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, userId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null;
                }
            }
        } catch (SQLException e) {
            System.err.println("Database Error: " + e.getMessage());
        }

        return null;
    }

    private boolean isOlderThanOneDay(String timestamp) {
        try {
            Instant lastSpinAt = Instant.parse(timestamp.replace(" ", "T"));
            return lastSpinAt.isBefore(Instant.now().minus(24, ChronoUnit.HOURS));
        } catch (Exception e) {
            return true;
        }
    }
}
