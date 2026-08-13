package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.TimeHelper;
import com.jwpaisley.models.SailboatPhoto;
import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.services.StorageService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UploadedFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SailboatPhotosController {
    private static final String PHOTO_BUCKET = "jwpaisley-sailboat-photos";
    private static final Pattern GCS_OBJECT_KEY_PATTERN = Pattern.compile("/jwpaisley-sailboat-photos/([^/?#]+)");

    public static SailboatPhoto sailboatPhotoFromResultSet(ResultSet rs) throws SQLException {
        return new SailboatPhoto(
            rs.getObject("id", UUID.class),
            rs.getObject("sailboat_id", UUID.class),
            rs.getObject("voyage_id", UUID.class),
            rs.getString("photo_url"),
            rs.getBoolean("show_in_carousel"),
            rs.getString("caption"),
            TimeHelper.toUtcIsoString(rs.getTimestamp("created_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing sailboat photos");
    }

    public void getAll(Context ctx) {
        List<SailboatPhoto> photos = new ArrayList<>();
        String sql = "SELECT * FROM public.sailboat_photos ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                photos.add(sailboatPhotoFromResultSet(rs));
            }
            ctx.json(photos);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getBySailboat(Context ctx) {
        UUID sailboatId = UUID.fromString(ctx.pathParam("sailboatId"));
        List<SailboatPhoto> photos = new ArrayList<>();
        String sql = "SELECT * FROM public.sailboat_photos WHERE sailboat_id = ?::uuid ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, sailboatId);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                photos.add(sailboatPhotoFromResultSet(rs));
            }
            ctx.json(photos);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM public.sailboat_photos WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                ctx.json(sailboatPhotoFromResultSet(rs));
            } else {
                throw new NotFoundResponse("Sailboat photo not found");
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

        SailboatPhoto newPhoto = ctx.bodyAsClass(SailboatPhoto.class);
        String sql = """
            INSERT INTO public.sailboat_photos (sailboat_id, voyage_id, photo_url, show_in_carousel, caption)
            VALUES (?, ?, ?, ?, ?)
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, newPhoto.sailboatId());
            pstmt.setObject(2, newPhoto.voyageId());
            pstmt.setString(3, newPhoto.photoUrl());
            pstmt.setBoolean(4, newPhoto.showInCarousel());
            pstmt.setString(5, newPhoto.caption());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    SailboatPhoto savedPhoto = sailboatPhotoFromResultSet(rs);
                    ctx.status(201).json(savedPhoto);
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error saving sailboat photo");
        }
    }

    public void update(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        SailboatPhoto updatedPhoto = ctx.bodyAsClass(SailboatPhoto.class);
        String sql = """
            UPDATE public.sailboat_photos
            SET sailboat_id = ?, voyage_id = ?, photo_url = ?, show_in_carousel = ?, caption = ?
            WHERE id = ?::uuid
            RETURNING *;
        """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, updatedPhoto.sailboatId());
            pstmt.setObject(2, updatedPhoto.voyageId());
            pstmt.setString(3, updatedPhoto.photoUrl());
            pstmt.setBoolean(4, updatedPhoto.showInCarousel());
            pstmt.setString(5, updatedPhoto.caption());
            pstmt.setObject(6, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(sailboatPhotoFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Sailboat photo not found");
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error updating sailboat photo");
        }
    }

    public void delete(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        String selectSql = "SELECT photo_url FROM public.sailboat_photos WHERE id = ?::uuid";
        String deleteSql = "DELETE FROM public.sailboat_photos WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            String photoUrl = null;
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                selectStmt.setObject(1, id);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (rs.next()) {
                        photoUrl = rs.getString("photo_url");
                    }
                }
            }

            try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                deleteStmt.setObject(1, id);
                int rowsDeleted = deleteStmt.executeUpdate();

                if (rowsDeleted > 0) {
                    deletePhotoObjectsFromStorage(photoUrl);
                    ctx.status(204);
                } else {
                    throw new NotFoundResponse("Sailboat photo not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    private void deletePhotoObjectsFromStorage(String photoUrl) {
        if (photoUrl == null || photoUrl.isBlank()) {
            return;
        }

        StorageService storageService = StorageService.getInstance();
        Matcher matcher = GCS_OBJECT_KEY_PATTERN.matcher(photoUrl);
        if (matcher.find()) {
            String objectKey = matcher.group(1);
            storageService.deleteFile(PHOTO_BUCKET, objectKey);
        }
    }

    public void uploadPhoto(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        StorageService storageService = StorageService.getInstance();

        try {
            UploadedFile file = ctx.uploadedFile("photo");
            if (file != null) {
                String objectKey = UUID.randomUUID().toString();
                String photoUrl = storageService.uploadFileWithObjectKey(file, PHOTO_BUCKET, objectKey).get("url");
                ctx.json(Map.of("url", photoUrl));
            } else {
                ctx.status(400).result("No file uploaded");
            }
        } catch (IOException e) {
            System.err.println("Storage error: " + e.getMessage());
            ctx.status(500).result("Error uploading sailboat photo");
        }
    }
}
