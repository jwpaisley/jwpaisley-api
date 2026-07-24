package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.models.Photo;
import com.jwpaisley.models.PhotoCollection;
import com.jwpaisley.services.DatabaseService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PhotoCollectionController {
    private static final int PAGE_SIZE = 5;

    public static PhotoCollection photoCollectionFromResultSet(ResultSet rs) throws SQLException {
        return new PhotoCollection(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("caption"),
            rs.getString("location"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing the photo collections archive");
    }

    private boolean isDatabaseConfigured() {
        String dbUrl = System.getenv("DB_URL");
        return dbUrl != null && !dbUrl.isBlank();
    }

    public void getAll(Context ctx) {
        if (!isDatabaseConfigured()) {
            Map<String, Object> response = new HashMap<>();
            response.put("items", List.of());
            response.put("nextPageToken", null);
            ctx.json(response);
            return;
        }

        String pageToken = ctx.queryParam("pageToken");
        int offset = 0;

        if (pageToken != null && !pageToken.isBlank()) {
            try {
                offset = Integer.parseInt(pageToken);
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid page token");
                return;
            }
        }

        List<PhotoCollection> collections = new ArrayList<>();
        String sql = """
            SELECT * FROM photo_collections
            ORDER BY created_at DESC, id DESC
            LIMIT ? OFFSET ?
            """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setInt(1, PAGE_SIZE + 1);
            pstmt.setInt(2, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    collections.add(photoCollectionFromResultSet(rs));
                }
            }

            List<PhotoCollection> pageItems = collections.size() > PAGE_SIZE
                ? collections.subList(0, PAGE_SIZE)
                : collections;

            String nextPageToken = collections.size() > PAGE_SIZE
                ? String.valueOf(offset + PAGE_SIZE)
                : null;

            Map<String, Object> response = new HashMap<>();
            response.put("items", pageItems);
            response.put("nextPageToken", nextPageToken);
            ctx.json(response);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        String idParam = ctx.pathParam("id");
        if (idParam == null || idParam.isBlank()) {
            ctx.status(400).result("Invalid collection id");
            return;
        }

        UUID id;
        try {
            id = UUID.fromString(idParam);
        } catch (IllegalArgumentException e) {
            ctx.status(400).result("Invalid collection id");
            return;
        }

        String sql = "SELECT * FROM photo_collections WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                ctx.json(photoCollectionFromResultSet(rs));
            } else {
                throw new NotFoundResponse("Photo collection not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getPhotosForCollection(Context ctx) {
        if (!isDatabaseConfigured()) {
            ctx.json(List.of());
            return;
        }

        String idParam = ctx.pathParam("id");
        if (idParam == null || idParam.isBlank()) {
            ctx.status(400).result("Invalid collection id");
            return;
        }

        UUID collectionId;
        try {
            collectionId = UUID.fromString(idParam);
        } catch (IllegalArgumentException e) {
            ctx.status(400).result("Invalid collection id");
            return;
        }

        List<Photo> photos = new ArrayList<>();
        String sql = """
            SELECT * FROM photos
            WHERE collection = ?::uuid
            ORDER BY created_at DESC, id DESC
            """;
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, collectionId);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    photos.add(PhotoController.photoFromResultSet(rs));
                }
            }

            ctx.json(photos);
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
        PhotoCollection newCollection = ctx.bodyAsClass(PhotoCollection.class);

        String sql = """
            INSERT INTO photo_collections (title, caption, location)
            VALUES (?, ?, ?)
            RETURNING *;
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newCollection.title());
            pstmt.setString(2, newCollection.caption());
            pstmt.setString(3, newCollection.location());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    PhotoCollection savedCollection = photoCollectionFromResultSet(rs);
                    ctx.status(201).json(savedCollection);
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error saving photo collection to the archive");
        }
    }

    public void update(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        String idParam = ctx.pathParam("id");
        if (idParam == null || idParam.isBlank()) {
            ctx.status(400).result("Invalid collection id");
            return;
        }

        UUID id;
        try {
            id = UUID.fromString(idParam);
        } catch (IllegalArgumentException e) {
            ctx.status(400).result("Invalid collection id");
            return;
        }

        PhotoCollection updatedCollection = ctx.bodyAsClass(PhotoCollection.class);
        String sql = """
            UPDATE photo_collections
            SET title = ?, caption = ?, location = ?
            WHERE id = ?::uuid
            RETURNING *;
        """;

        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, updatedCollection.title());
            pstmt.setString(2, updatedCollection.caption());
            pstmt.setString(3, updatedCollection.location());
            pstmt.setObject(4, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(200).json(photoCollectionFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Photo collection not found");
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

        String idParam = ctx.pathParam("id");
        if (idParam == null || idParam.isBlank()) {
            ctx.status(400).result("Invalid collection id");
            return;
        }

        UUID id;
        try {
            id = UUID.fromString(idParam);
        } catch (IllegalArgumentException e) {
            ctx.status(400).result("Invalid collection id");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);

            try {
                try (PreparedStatement deletePhotosStmt = conn.prepareStatement("DELETE FROM photos WHERE collection = ?::uuid")) {
                    deletePhotosStmt.setObject(1, id);
                    deletePhotosStmt.executeUpdate();
                }

                try (PreparedStatement deleteCollectionStmt = conn.prepareStatement("DELETE FROM photo_collections WHERE id = ?::uuid")) {
                    deleteCollectionStmt.setObject(1, id);
                    int rowsDeleted = deleteCollectionStmt.executeUpdate();

                    if (rowsDeleted > 0) {
                        conn.commit();
                        ctx.status(204);
                    } else {
                        conn.rollback();
                        throw new NotFoundResponse("Photo collection not found");
                    }
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
