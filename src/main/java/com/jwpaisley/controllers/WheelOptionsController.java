package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.TimeHelper;
import com.jwpaisley.models.WheelOption;
import com.jwpaisley.services.DatabaseService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class WheelOptionsController {
    public static WheelOption wheelOptionFromResultSet(ResultSet rs) throws SQLException {
        return new WheelOption(
            rs.getObject("id", UUID.class),
            rs.getInt("value"),
            rs.getBigDecimal("probability"),
            TimeHelper.toUtcIsoString(rs.getTimestamp("created_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing wheel options");
    }

    private boolean validateProbabilityTotal(List<WheelOption> options) {
        BigDecimal total = BigDecimal.ZERO;
        for (WheelOption option : options) {
            total = total.add(option.probability());
        }
        return total.compareTo(BigDecimal.ONE) == 0;
    }

    public void getAll(Context ctx) {
        List<WheelOption> wheelOptions = new ArrayList<>();
        String sql = "SELECT * FROM public.wheel_options ORDER BY value ASC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                wheelOptions.add(wheelOptionFromResultSet(rs));
            }
            ctx.json(wheelOptions);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM public.wheel_options WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(wheelOptionFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Wheel option not found");
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

        WheelOption newOption = ctx.bodyAsClass(WheelOption.class);
        List<WheelOption> allOptions = getAllOptions();
        allOptions.add(newOption);

        if (!validateProbabilityTotal(allOptions)) {
            ctx.status(400).result("Wheel option probabilities must sum to 1");
            return;
        }

        String sql = "INSERT INTO public.wheel_options (value, probability) VALUES (?, ?) RETURNING *;";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, newOption.value());
            pstmt.setBigDecimal(2, newOption.probability());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(wheelOptionFromResultSet(rs));
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
        WheelOption updatedOption = ctx.bodyAsClass(WheelOption.class);
        List<WheelOption> allOptions = getAllOptions();

        allOptions.removeIf(option -> option.id() != null && option.id().equals(id));
        allOptions.add(updatedOption);

        if (!validateProbabilityTotal(allOptions)) {
            ctx.status(400).result("Wheel option probabilities must sum to 1");
            return;
        }

        String sql = "UPDATE public.wheel_options SET value = ?, probability = ? WHERE id = ?::uuid RETURNING *;";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, updatedOption.value());
            pstmt.setBigDecimal(2, updatedOption.probability());
            pstmt.setObject(3, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(200).json(wheelOptionFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Wheel option not found");
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
        List<WheelOption> allOptions = getAllOptions();
        boolean removed = allOptions.removeIf(option -> option.id() != null && option.id().equals(id));

        if (!removed) {
            ctx.status(404).result("Wheel option not found");
            return;
        }

        if (!validateProbabilityTotal(allOptions)) {
            ctx.status(400).result("Wheel option probabilities must sum to 1");
            return;
        }

        String sql = "DELETE FROM public.wheel_options WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Wheel option not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    private List<WheelOption> getAllOptions() {
        List<WheelOption> options = new ArrayList<>();
        String sql = "SELECT * FROM public.wheel_options";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                options.add(wheelOptionFromResultSet(rs));
            }
        } catch (SQLException e) {
            throw new RuntimeException("Error fetching wheel options", e);
        }

        return options;
    }
}
