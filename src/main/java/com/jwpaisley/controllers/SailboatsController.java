package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.models.Sailboat;
import com.jwpaisley.services.DatabaseService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class SailboatsController {

    private static final String DEFAULT_PRIVATE_ENSIGN_FLAG_URL =
        "https://storage.googleapis.com/jwpaisley-sailboat-private-ensign-flags/blank.png";

    private static String normalizePrivateEnsignFlagUrl(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_PRIVATE_ENSIGN_FLAG_URL;
        }
        return value;
    }

    public static Sailboat sailboatFromResultSet(ResultSet rs) throws SQLException {
        return new Sailboat(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("mmsi"),
            rs.getString("hin_cin"),
            rs.getString("official_number"),
            rs.getString("flag_state"),
            rs.getString("call_sign"),
            rs.getString("make"),
            rs.getString("manufacturer"),
            rs.getString("model"),
            rs.getObject("year_built", Integer.class),
            rs.getString("designer"),
            rs.getString("hull_type"),
            rs.getString("hull_material"),
            rs.getString("keel_type"),
            rs.getString("rig_type"),
            rs.getBigDecimal("loa"),
            rs.getBigDecimal("lwl"),
            rs.getBigDecimal("beam_ft"),
            rs.getBigDecimal("draft_min"),
            rs.getBigDecimal("draft_max"),
            rs.getBigDecimal("displacement_weight"),
            rs.getBigDecimal("ballast_weight"),
            rs.getBigDecimal("sail_area"),
            rs.getString("phrf_rating"),
            rs.getString("orc_rating"),
            rs.getString("engine_make_model"),
            rs.getObject("engine_hp", Integer.class),
            rs.getBigDecimal("fuel_capacity_gal"),
            rs.getBigDecimal("freshwater_capacity_gal"),
            rs.getBigDecimal("holding_tank_capacity_gal"),
            rs.getString("home_port"),
            normalizePrivateEnsignFlagUrl(rs.getString("private_ensign_flag_url")),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sailboats");
    }

    public void getAll(Context ctx) {
        List<Sailboat> sailboats = new ArrayList<>();
        String sql = "SELECT * FROM public.sailboats ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                sailboats.add(sailboatFromResultSet(rs));
            }
            ctx.json(sailboats);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM public.sailboats WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sailboatFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sailboat not found");
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
        Sailboat newSailboat = ctx.bodyAsClass(Sailboat.class);
        String privateEnsignFlagUrl = normalizePrivateEnsignFlagUrl(newSailboat.privateEnsignFlagUrl());

        String placeholders = String.join(", ", Collections.nCopies(32, "?"));
        String sql = """
            INSERT INTO public.sailboats (
                name, mmsi, hin_cin, official_number, flag_state, call_sign,
                make, manufacturer, model, year_built, designer, hull_type,
                hull_material, keel_type, rig_type, loa, lwl, beam_ft, draft_min,
                draft_max, displacement_weight, ballast_weight, sail_area,
                phrf_rating, orc_rating, engine_make_model, engine_hp,
                fuel_capacity_gal, freshwater_capacity_gal, holding_tank_capacity_gal,
                home_port, private_ensign_flag_url
            ) VALUES (%s)
            RETURNING *;
        """.formatted(placeholders);

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newSailboat.name());
            pstmt.setString(2, newSailboat.mmsi());
            pstmt.setString(3, newSailboat.hinCin());
            pstmt.setString(4, newSailboat.officialNumber());
            pstmt.setString(5, newSailboat.flagState());
            pstmt.setString(6, newSailboat.callSign());
            pstmt.setString(7, newSailboat.make());
            pstmt.setString(8, newSailboat.manufacturer());
            pstmt.setString(9, newSailboat.model());
            pstmt.setObject(10, newSailboat.yearBuilt());
            pstmt.setString(11, newSailboat.designer());
            pstmt.setString(12, newSailboat.hullType());
            pstmt.setString(13, newSailboat.hullMaterial());
            pstmt.setString(14, newSailboat.keelType());
            pstmt.setString(15, newSailboat.rigType());
            pstmt.setBigDecimal(16, newSailboat.loa());
            pstmt.setBigDecimal(17, newSailboat.lwl());
            pstmt.setBigDecimal(18, newSailboat.beamFt());
            pstmt.setBigDecimal(19, newSailboat.draftMin());
            pstmt.setBigDecimal(20, newSailboat.draftMax());
            pstmt.setBigDecimal(21, newSailboat.displacementWeight());
            pstmt.setBigDecimal(22, newSailboat.ballastWeight());
            pstmt.setBigDecimal(23, newSailboat.sailArea());
            pstmt.setString(24, newSailboat.phrfRating());
            pstmt.setString(25, newSailboat.orcRating());
            pstmt.setString(26, newSailboat.engineMakeModel());
            pstmt.setObject(27, newSailboat.engineHp());
            pstmt.setBigDecimal(28, newSailboat.fuelCapacityGal());
            pstmt.setBigDecimal(29, newSailboat.freshwaterCapacityGal());
            pstmt.setBigDecimal(30, newSailboat.holdingTankCapacityGal());
            pstmt.setString(31, newSailboat.homePort());
            pstmt.setString(32, privateEnsignFlagUrl);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(sailboatFromResultSet(rs));
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
        Sailboat updatedSailboat = ctx.bodyAsClass(Sailboat.class);
        String privateEnsignFlagUrl = normalizePrivateEnsignFlagUrl(updatedSailboat.privateEnsignFlagUrl());

        String sql = """
            UPDATE public.sailboats SET
                name = ?, mmsi = ?, hin_cin = ?, official_number = ?, flag_state = ?, call_sign = ?,
                make = ?, manufacturer = ?, model = ?, year_built = ?, designer = ?, hull_type = ?,
                hull_material = ?, keel_type = ?, rig_type = ?, loa = ?, lwl = ?, beam_ft = ?, draft_min = ?,
                draft_max = ?, displacement_weight = ?, ballast_weight = ?, sail_area = ?,
                phrf_rating = ?, orc_rating = ?, engine_make_model = ?, engine_hp = ?,
                fuel_capacity_gal = ?, freshwater_capacity_gal = ?, holding_tank_capacity_gal = ?,
                home_port = ?, private_ensign_flag_url = ?
            WHERE id = ?::uuid
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, updatedSailboat.name());
            pstmt.setString(2, updatedSailboat.mmsi());
            pstmt.setString(3, updatedSailboat.hinCin());
            pstmt.setString(4, updatedSailboat.officialNumber());
            pstmt.setString(5, updatedSailboat.flagState());
            pstmt.setString(6, updatedSailboat.callSign());
            pstmt.setString(7, updatedSailboat.make());
            pstmt.setString(8, updatedSailboat.manufacturer());
            pstmt.setString(9, updatedSailboat.model());
            pstmt.setObject(10, updatedSailboat.yearBuilt());
            pstmt.setString(11, updatedSailboat.designer());
            pstmt.setString(12, updatedSailboat.hullType());
            pstmt.setString(13, updatedSailboat.hullMaterial());
            pstmt.setString(14, updatedSailboat.keelType());
            pstmt.setString(15, updatedSailboat.rigType());
            pstmt.setBigDecimal(16, updatedSailboat.loa());
            pstmt.setBigDecimal(17, updatedSailboat.lwl());
            pstmt.setBigDecimal(18, updatedSailboat.beamFt());
            pstmt.setBigDecimal(19, updatedSailboat.draftMin());
            pstmt.setBigDecimal(20, updatedSailboat.draftMax());
            pstmt.setBigDecimal(21, updatedSailboat.displacementWeight());
            pstmt.setBigDecimal(22, updatedSailboat.ballastWeight());
            pstmt.setBigDecimal(23, updatedSailboat.sailArea());
            pstmt.setString(24, updatedSailboat.phrfRating());
            pstmt.setString(25, updatedSailboat.orcRating());
            pstmt.setString(26, updatedSailboat.engineMakeModel());
            pstmt.setObject(27, updatedSailboat.engineHp());
            pstmt.setBigDecimal(28, updatedSailboat.fuelCapacityGal());
            pstmt.setBigDecimal(29, updatedSailboat.freshwaterCapacityGal());
            pstmt.setBigDecimal(30, updatedSailboat.holdingTankCapacityGal());
            pstmt.setString(31, updatedSailboat.homePort());
            pstmt.setString(32, privateEnsignFlagUrl);
            pstmt.setObject(33, updatedSailboat.id());

            int rowsUpdated = pstmt.executeUpdate();

            if (rowsUpdated > 0) {
                ctx.status(200).json(updatedSailboat);
            } else {
                throw new NotFoundResponse("Sailboat not found");
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
        String sql = "DELETE FROM public.sailboats WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Sailboat not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
