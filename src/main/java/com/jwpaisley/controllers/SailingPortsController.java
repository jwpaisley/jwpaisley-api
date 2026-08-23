package com.jwpaisley.controllers;

import com.jwpaisley.helpers.LoggingHelper;
import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.TimeHelper;
import com.jwpaisley.models.SailingPortConditionHistory;
import com.jwpaisley.models.SailingPort;
import com.jwpaisley.models.SailingPortConditions;
import com.jwpaisley.models.SailingPortWithConditions;
import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.services.SailingPortConditionsService;
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
import java.util.concurrent.CompletableFuture;
import java.time.Instant;

public class SailingPortsController {
    private final SailingPortConditionsService conditionsService = SailingPortConditionsService.getInstance();
    private record RefreshSummary(int attempted, int refreshed, int failed) {}

    public SailingPortsController() {
    }

    public static SailingPort sailingPortFromResultSet(ResultSet rs) throws SQLException {
        return new SailingPort(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"),
            TimeHelper.toUtcIsoString(rs.getTimestamp("created_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private SailingPortConditionHistory sailingPortConditionHistoryFromResultSet(ResultSet rs) throws SQLException {
        return new SailingPortConditionHistory(
            rs.getObject("id", UUID.class),
            rs.getObject("sailing_port_id", UUID.class),
            rs.getObject("wind_speed", Double.class),
            rs.getObject("wind_direction", Double.class),
            rs.getObject("gust_speed", Double.class),
            rs.getObject("current_speed", Double.class),
            rs.getObject("current_direction", Double.class),
            rs.getObject("wave_height", Double.class),
            rs.getObject("wave_period", Double.class),
            rs.getObject("water_temperature", Double.class),
            rs.getObject("air_temperature", Double.class),
            rs.getObject("cloud_cover", Double.class),
            rs.getObject("precipitation", Double.class),
            rs.getObject("visibility", Double.class),
            rs.getString("weather"),
            TimeHelper.toUtcIsoString(rs.getTimestamp("forecast_time")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("fetched_at")),
            rs.getString("raw_response")
        );
    }

    private void handleError(Context ctx, Exception e) {
        LoggingHelper.error("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sailing ports");
    }

    private List<SailingPort> fetchAllPorts() throws SQLException {
        List<SailingPort> ports = new ArrayList<>();
        String sql = "SELECT * FROM public.sailing_ports ORDER BY name ASC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                ports.add(sailingPortFromResultSet(rs));
            }
        }

        return ports;
    }

    private RefreshSummary refreshAllConditions() throws SQLException {
        List<SailingPort> ports = fetchAllPorts();
        int refreshedCount = 0;
        int failedCount = 0;

        LoggingHelper.info("Sailing conditions job discovered " + ports.size() + " port(s) to refresh");

        for (SailingPort port : ports) {
            try {
                SailingPortConditions refreshed = conditionsService.refreshConditions(port);
                if (refreshed != null) {
                    refreshedCount++;
                    LoggingHelper.debug("Sailing conditions refreshed for port " + port.name() + " (" + port.id() + ")");
                } else {
                    failedCount++;
                    LoggingHelper.warning("Sailing conditions refresh returned null for port " + port.name() + " (" + port.id() + ")");
                }
            } catch (Exception e) {
                failedCount++;
                LoggingHelper.error("Sailing conditions refresh failed for port " + port.name() + " (" + port.id() + "): " + e.getMessage());
            }
        }

        return new RefreshSummary(ports.size(), refreshedCount, failedCount);
    }

    private SailingPortWithConditions hydrateWithConditions(SailingPort port) {
        SailingPortConditions conditions = conditionsService.getConditions(port);
        return SailingPortWithConditions.from(port, conditions);
    }

    public void getAll(Context ctx) {
        List<SailingPortWithConditions> ports = new ArrayList<>();
        try {
            for (SailingPort port : fetchAllPorts()) {
                ports.add(hydrateWithConditions(port));
            }
            ctx.json(ports);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM public.sailing_ports WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(hydrateWithConditions(sailingPortFromResultSet(rs)));
                } else {
                    throw new NotFoundResponse("Sailing port not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getHistory(Context ctx) {
        UUID portId = UUID.fromString(ctx.pathParam("id"));
        int limit = 96;
        String rawLimit = ctx.queryParam("limit");
        if (rawLimit != null && !rawLimit.isBlank()) {
            try {
                limit = Math.max(1, Math.min(500, Integer.parseInt(rawLimit)));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid limit query parameter");
                return;
            }
        }

        String sql = """
            SELECT *
            FROM public.sailing_port_condition_history
            WHERE sailing_port_id = ?::uuid
            ORDER BY fetched_at DESC
            LIMIT ?
        """;

        DataSource ds = DatabaseService.getInstance().getDataSource();
        List<SailingPortConditionHistory> history = new ArrayList<>();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, portId);
            pstmt.setInt(2, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    history.add(sailingPortConditionHistoryFromResultSet(rs));
                }
            }
            ctx.json(history);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void fetchConditionsJob(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !(AuthHelper.isServiceAccount(ctx) || AuthHelper.isAdmin(ctx))) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        String runId = UUID.randomUUID().toString();
        String requestor = AuthHelper.getCurrentUserEmail(ctx);
        Instant startAt = Instant.now();

        LoggingHelper.info("Starting sailing conditions fetch job runId=" + runId + ", requestedBy=" + requestor + ", at=" + startAt);

        CompletableFuture.runAsync(() -> {
            try {
                RefreshSummary summary = refreshAllConditions();
                LoggingHelper.success(
                    "Completed sailing conditions fetch job runId=" + runId
                        + ", attempted=" + summary.attempted()
                        + ", refreshed=" + summary.refreshed()
                        + ", failed=" + summary.failed()
                );
            } catch (Exception e) {
                LoggingHelper.error("Sailing conditions fetch job failed runId=" + runId + ": " + e.getMessage());
            }
        });

        ctx.status(200).result("Sailing conditions fetch job started: " + runId);
    }

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();
        SailingPort newPort = ctx.bodyAsClass(SailingPort.class);

        String sql = """
            INSERT INTO public.sailing_ports (
                name, latitude, longitude
            ) VALUES (?, ?, ?)
            RETURNING id, name, latitude, longitude, created_at, updated_at;
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newPort.name());
            pstmt.setBigDecimal(2, newPort.latitude());
            pstmt.setBigDecimal(3, newPort.longitude());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(sailingPortFromResultSet(rs));
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

        DataSource ds = DatabaseService.getInstance().getDataSource();
        SailingPort updatedPort = ctx.bodyAsClass(SailingPort.class);

        String sql = """
            UPDATE public.sailing_ports SET
                name = ?, latitude = ?, longitude = ?, updated_at = CURRENT_TIMESTAMP
            WHERE id = ?::uuid
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, updatedPort.name());
            pstmt.setBigDecimal(2, updatedPort.latitude());
            pstmt.setBigDecimal(3, updatedPort.longitude());
            pstmt.setObject(4, updatedPort.id());

            int rowsUpdated = pstmt.executeUpdate();

            if (rowsUpdated > 0) {
                ctx.status(200).json(updatedPort);
            } else {
                throw new NotFoundResponse("Sailing port not found");
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
        String deleteHistorySql = "DELETE FROM public.sailing_port_condition_history WHERE sailing_port_id = ?::uuid";
        String sql = "DELETE FROM public.sailing_ports WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement deleteHistoryStmt = conn.prepareStatement(deleteHistorySql);
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            deleteHistoryStmt.setObject(1, id);
            deleteHistoryStmt.executeUpdate();
            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Sailing port not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
