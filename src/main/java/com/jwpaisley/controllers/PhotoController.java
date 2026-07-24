package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.models.Photo;
import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.services.StorageService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UploadedFile;

import javax.sql.DataSource;
import java.io.IOException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class PhotoController {
    private static final String PHOTO_BUCKET = "jwpaisley-photos";
    private static final Pattern GCS_OBJECT_KEY_PATTERN = Pattern.compile("/jwpaisley-photos/([^/?#]+)");

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

    static Date normalizeTakenDate(String takenDate) {
        if (takenDate == null || takenDate.isBlank()) {
            return null;
        }

        String trimmed = takenDate.trim();

        try {
            return Date.valueOf(trimmed);
        } catch (IllegalArgumentException ignored) {
            try {
                return Date.valueOf(OffsetDateTime.parse(trimmed).toLocalDate().toString());
            } catch (DateTimeParseException | IllegalArgumentException ignored2) {
                return null;
            }
        }
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

    public void getByCollection(Context ctx) {
        UUID collectionId = UUID.fromString(ctx.pathParam("collectionId"));
        List<Photo> photos = new ArrayList<>();
        String sql = "SELECT * FROM photos WHERE collection = ?::uuid ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, collectionId);
            ResultSet rs = pstmt.executeQuery();

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

            Date takenDate = normalizeTakenDate(newPhoto.takenDate());

            pstmt.setObject(1, newPhoto.collection());
            pstmt.setString(2, newPhoto.image());
            pstmt.setString(3, newPhoto.caption());
            pstmt.setString(4, newPhoto.location());
            if (takenDate == null) {
                pstmt.setNull(5, java.sql.Types.DATE);
            } else {
                pstmt.setDate(5, takenDate);
            }

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
        String selectSql = "SELECT image FROM photos WHERE id = ?::uuid";
        String deleteSql = "DELETE FROM photos WHERE id = ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            String imageUrl = null;
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                selectStmt.setObject(1, id);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (rs.next()) {
                        imageUrl = rs.getString("image");
                    }
                }
            }

            try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                deleteStmt.setObject(1, id);
                int rowsDeleted = deleteStmt.executeUpdate();

                if (rowsDeleted > 0) {
                    deletePhotoObjectsFromStorage(imageUrl);
                    ctx.status(204);
                } else {
                    throw new NotFoundResponse("Photo not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    private void deletePhotoObjectsFromStorage(String imageUrl) {
        if (imageUrl == null || imageUrl.isBlank()) {
            return;
        }

        StorageService storageService = StorageService.getInstance();
        Matcher matcher = GCS_OBJECT_KEY_PATTERN.matcher(imageUrl);
        if (matcher.find()) {
            String objectKey = matcher.group(1);
            storageService.deleteFile(PHOTO_BUCKET, objectKey);
            storageService.deleteFile(PHOTO_BUCKET, "thumb-" + objectKey);
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
                Map<String, String> uploadResult = storageService.uploadFileWithObjectKey(file, PHOTO_BUCKET, objectKey);
                ctx.json(uploadResult);
            } else {
                ctx.status(400).result("No file uploaded");
            }
        } catch (IOException e) {
            System.err.println("Storage error: " + e.getMessage());
            ctx.status(500).result("Error uploading photo");
        }
    }
}
