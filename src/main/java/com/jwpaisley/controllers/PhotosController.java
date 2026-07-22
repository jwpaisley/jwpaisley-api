package com.jwpaisley.controllers;

import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.services.StorageService;
import com.jwpaisley.models.Photo;
import com.jwpaisley.helpers.AuthHelper;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UploadedFile;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.io.IOException;

public class PhotosController {

    public static Photo photoFromResultSet(ResultSet rs) throws SQLException {
        return new Photo(
            rs.getObject("id", UUID.class),
            rs.getObject("collection", UUID.class),
            rs.getString("image"),
            rs.getString("caption"),
            rs.getString("location"),
            rs.getString("taken_date"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }
    
    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing the photos archive");
    }

    public void getAll(Context ctx) {
        List<Photo> photos = new ArrayList<>();
        String sql = "SELECT * FROM photos ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                photos.add(photoFromResultSet(rs));
            }
            ctx.json(photos);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM photos WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                ctx.json(photoFromResultSet(rs));
            } else {
                throw new NotFoundResponse("Photo not found");
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
        Photo newPhoto = ctx.bodyAsClass(Photo.class);

        String sql = """
            INSERT INTO photos (collection, image, caption, location, taken_date)
            VALUES (?, ?, ?, ?, ?)
            RETURNING *;
        """;

        try (Connection conn = ds.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, newPhoto.collection());
            pstmt.setString(2, newPhoto.image());
            pstmt.setString(3, newPhoto.caption());
            pstmt.setString(4, newPhoto.location());
            pstmt.setString(5, newPhoto.takenDate());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Photo savedPhoto = photoFromResultSet(rs);
                    ctx.status(201).json(savedPhoto);
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error saving photo to the archive");
        }
    }

    public void delete(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "DELETE FROM photos WHERE id = ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Photo not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
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
                String fileUrl = storageService.uploadFile(file, "jwpaisley-photos");
                ctx.json(Map.of("url", fileUrl));
            } else {
                ctx.status(400).result("No file uploaded");
            }
        } catch (IOException e) {
            System.err.println("Storage error: " + e.getMessage());
            ctx.status(500).result("Error uploading photo");
        }
    }
}