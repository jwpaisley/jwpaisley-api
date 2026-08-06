package com.jwpaisley.controllers;

import com.jwpaisley.helpers.LoggingHelper;
import com.jwpaisley.helpers.AuthHelper;
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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class SailingPortsController {
    private final SailingPortConditionsService conditionsService = SailingPortConditionsService.getInstance();

    public SailingPortsController() {
        startPollingLoop();
    }

    public static SailingPort sailingPortFromResultSet(ResultSet rs) throws SQLException {
        return new SailingPort(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getBigDecimal("latitude"),
            rs.getBigDecimal("longitude"),
            rs.getString("tide_station_id"),
            rs.getString("current_station_id"),
            rs.getString("buoy_station_id"),
            rs.getString("observation_station_id"),
            rs.getString("nws_office"),
            rs.getObject("nws_grid_x", Integer.class),
            rs.getObject("nws_grid_y", Integer.class),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        LoggingHelper.error("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sailing ports");
    }

    private void startPollingLoop() {
        Thread pollingThread = new Thread(() -> {
            while (true) {
                try {
                    refreshAllConditions();
                } catch (Exception e) {
                    LoggingHelper.error("Sailing port polling failed: " + e.getMessage());
                }

                try {
                    Thread.sleep(getSleepMillisUntilNextQuarterHour());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }, "sailing-port-poller");
        pollingThread.setDaemon(true);
        pollingThread.start();
    }

    private long getSleepMillisUntilNextQuarterHour() {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
        int minute = now.getMinute();
        int nextMinute = minute < 15 ? 15 : minute < 30 ? 30 : minute < 45 ? 45 : 0;
        LocalDateTime nextTick = now.withSecond(0).withNano(0);

        if (nextMinute == 0) {
            nextTick = nextTick.plusHours(1).withMinute(0).withSecond(0).withNano(0);
        } else {
            nextTick = nextTick.withMinute(nextMinute).withSecond(0).withNano(0);
        }

        Instant nowInstant = Instant.now();
        Instant nextInstant = nextTick.toInstant(ZoneOffset.UTC);
        return Duration.between(nowInstant, nextInstant).toMillis();
    }

    private void refreshAllConditions() {
        List<SailingPort> ports = new ArrayList<>();
        String sql = "SELECT * FROM public.sailing_ports ORDER BY name ASC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {
            while (rs.next()) {
                ports.add(sailingPortFromResultSet(rs));
            }
        } catch (SQLException e) {
            LoggingHelper.error("Unable to refresh sailing port conditions: " + e.getMessage());
            return;
        }

        for (SailingPort port : ports) {
            conditionsService.refreshConditions(port);
        }
    }

    private SailingPortWithConditions hydrateWithConditions(SailingPort port) {
        SailingPortConditions conditions = conditionsService.getConditions(port);
        return SailingPortWithConditions.from(port, conditions);
    }

    public void getAll(Context ctx) {
        List<SailingPortWithConditions> ports = new ArrayList<>();
        String sql = "SELECT * FROM public.sailing_ports ORDER BY name ASC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                ports.add(hydrateWithConditions(sailingPortFromResultSet(rs)));
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

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();
        SailingPort newPort = ctx.bodyAsClass(SailingPort.class);

        String sql = """
            INSERT INTO public.sailing_ports (
                name, latitude, longitude, tide_station_id, current_station_id,
                buoy_station_id, observation_station_id, nws_office, nws_grid_x, nws_grid_y
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *;
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newPort.name());
            pstmt.setBigDecimal(2, newPort.latitude());
            pstmt.setBigDecimal(3, newPort.longitude());
            pstmt.setString(4, newPort.tideStationId());
            pstmt.setString(5, newPort.currentStationId());
            pstmt.setString(6, newPort.buoyStationId());
            pstmt.setString(7, newPort.observationStationId());
            pstmt.setString(8, newPort.nwsOffice());
            pstmt.setObject(9, newPort.nwsGridX());
            pstmt.setObject(10, newPort.nwsGridY());

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
                name = ?, latitude = ?, longitude = ?, tide_station_id = ?,
                current_station_id = ?, buoy_station_id = ?, observation_station_id = ?,
                nws_office = ?, nws_grid_x = ?, nws_grid_y = ?
            WHERE id = ?::uuid
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, updatedPort.name());
            pstmt.setBigDecimal(2, updatedPort.latitude());
            pstmt.setBigDecimal(3, updatedPort.longitude());
            pstmt.setString(4, updatedPort.tideStationId());
            pstmt.setString(5, updatedPort.currentStationId());
            pstmt.setString(6, updatedPort.buoyStationId());
            pstmt.setString(7, updatedPort.observationStationId());
            pstmt.setString(8, updatedPort.nwsOffice());
            pstmt.setObject(9, updatedPort.nwsGridX());
            pstmt.setObject(10, updatedPort.nwsGridY());
            pstmt.setObject(11, updatedPort.id());

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
        String sql = "DELETE FROM public.sailing_ports WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

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
