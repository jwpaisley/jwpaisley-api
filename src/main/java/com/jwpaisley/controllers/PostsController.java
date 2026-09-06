package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.TimeHelper;
import com.jwpaisley.models.Post;
import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.services.StorageService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UploadedFile;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PostsController {
    private static final String POST_MEDIA_BUCKET = "jwpaisley-post-media";

    public static Post postFromResultSet(ResultSet rs) throws SQLException {
        return new Post(
            rs.getObject("id", UUID.class),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("cover_photo_url"),
            Post.PostType.fromDbValue(rs.getString("type")),
            rs.getString("content"),
            Post.PostStatus.fromDbValue(rs.getString("status")),
            rs.getObject("reading_time", Integer.class),
            rs.getObject("view_count", Integer.class),
            TimeHelper.toUtcIsoString(rs.getTimestamp("published_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("archived_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("created_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private static Timestamp parseNullableInstant(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }

        return Timestamp.from(Instant.parse(value));
    }

    private static int calculateReadingTimeMinutes(String content) {
        if (content == null || content.isBlank()) {
            return 1;
        }

        String normalized = content.trim();
        if (normalized.isEmpty()) {
            return 1;
        }

        long wordCount = java.util.Arrays.stream(normalized.split("\\s+"))
            .filter(word -> !word.isBlank())
            .count();

        return Math.max(1, (int) Math.ceil(wordCount / 200.0));
    }

    static boolean shouldIncrementViewCount(boolean isAdmin) {
        return !isAdmin;
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing posts");
    }

    public void getAll(Context ctx) {
        String pageToken = ctx.queryParam("pageToken");
        if (pageToken == null || pageToken.isBlank()) {
            pageToken = ctx.queryParam("nextPageToken");
        }

        int offset = 0;
        if (pageToken != null && !pageToken.isBlank()) {
            try {
                offset = Math.max(0, Integer.parseInt(pageToken));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid page token");
                return;
            }
        }

        int pageSize = 20;
        String rawPageSize = ctx.queryParam("pageSize");
        if (rawPageSize != null && !rawPageSize.isBlank()) {
            try {
                pageSize = Math.max(1, Integer.parseInt(rawPageSize));
            } catch (NumberFormatException e) {
                ctx.status(400).result("Invalid pageSize query parameter");
                return;
            }
        }

        String rawType = ctx.queryParam("type");
        String rawDraftsOnly = ctx.queryParam("drafts");
        boolean draftsOnly = rawDraftsOnly != null && Boolean.parseBoolean(rawDraftsOnly);

        List<Post> posts = new ArrayList<>();
        DataSource ds = DatabaseService.getInstance().getDataSource();

        StringBuilder sqlBuilder = new StringBuilder("SELECT * FROM posts");
        List<Object> params = new ArrayList<>();
        boolean hasWhereClause = false;

        if (rawType != null && !rawType.isBlank()) {
            try {
                Post.PostType postType = Post.PostType.fromDbValue(rawType);
                sqlBuilder.append(" WHERE type = ?::post_type");
                params.add(postType.dbValue());
                hasWhereClause = true;
            } catch (IllegalArgumentException e) {
                ctx.status(400).result("Invalid post type");
                return;
            }
        }

        String statusFilter = draftsOnly ? "draft" : "published";
        if (hasWhereClause) {
            sqlBuilder.append(" AND status = ?::post_status");
        } else {
            sqlBuilder.append(" WHERE status = ?::post_status");
        }
        params.add(statusFilter);

        sqlBuilder.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        params.add(pageSize + 1);
        params.add(offset);

        String sql = sqlBuilder.toString();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            for (int i = 0; i < params.size(); i++) {
                Object param = params.get(i);
                if (param instanceof String stringValue) {
                    pstmt.setString(i + 1, stringValue);
                } else if (param instanceof Integer intValue) {
                    pstmt.setInt(i + 1, intValue);
                }
            }

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    posts.add(postFromResultSet(rs));
                }
            }

            List<Post> pageItems = posts.size() > pageSize ? posts.subList(0, pageSize) : posts;
            String nextPageToken = posts.size() > pageSize ? String.valueOf(offset + pageSize) : null;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("items", pageItems);
            response.put("nextPageToken", nextPageToken);
            ctx.json(response);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void uploadMedia(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        StorageService storageService = StorageService.getInstance();

        try {
            UploadedFile file = ctx.uploadedFile("file");
            if (file == null) {
                ctx.status(400).result("No file uploaded");
                return;
            }

            String fileUrl = storageService.uploadFile(file, POST_MEDIA_BUCKET);
            ctx.json(Map.of("url", fileUrl));
        } catch (Exception e) {
            System.err.println("Storage error: " + e.getMessage());
            ctx.status(500).result("Error uploading post media");
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        DataSource ds = DatabaseService.getInstance().getDataSource();

        String selectSql = "SELECT * FROM posts WHERE id = ?::uuid";
        String incrementSql = "UPDATE posts SET view_count = view_count + 1, updated_at = CURRENT_TIMESTAMP WHERE id = ?::uuid";

        try (Connection conn = ds.getConnection()) {
            conn.setAutoCommit(false);

            Post post;
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                selectStmt.setObject(1, id);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new NotFoundResponse("Post not found");
                    }
                    post = postFromResultSet(rs);
                }
            }

            boolean isAuthenticated = AuthHelper.validateOAuthToken(ctx);
            boolean isAdmin = isAuthenticated && AuthHelper.isAdmin(ctx);
            if (shouldIncrementViewCount(isAdmin)) {
                try (PreparedStatement incrementStmt = conn.prepareStatement(incrementSql)) {
                    incrementStmt.setObject(1, id);
                    int rowsUpdated = incrementStmt.executeUpdate();
                    if (rowsUpdated == 0) {
                        throw new NotFoundResponse("Post not found");
                    }
                }
            }

            try (PreparedStatement refreshedStmt = conn.prepareStatement(selectSql)) {
                refreshedStmt.setObject(1, id);
                try (ResultSet rs = refreshedStmt.executeQuery()) {
                    if (rs.next()) {
                        conn.commit();
                        ctx.json(postFromResultSet(rs));
                        return;
                    }
                }
            }

            conn.rollback();
            throw new NotFoundResponse("Post not found");
        } catch (SQLException e) {
            handleError(ctx, e);
        } catch (NotFoundResponse e) {
            throw e;
        }
    }

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();
        Post newPost = ctx.bodyAsClass(Post.class);
        int readingTimeMinutes = calculateReadingTimeMinutes(newPost.content());

        String sql = """
            INSERT INTO posts (
                title,
                description,
                cover_photo_url,
                type,
                content,
                status,
                reading_time,
                view_count,
                published_at,
                archived_at
            ) VALUES (?, ?, ?, ?::post_type, ?, ?::post_status, ?, ?, ?, ?)
            RETURNING *;
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newPost.title() == null || newPost.title().isBlank() ? newPost.description() : newPost.title());
            pstmt.setString(2, newPost.description());
            pstmt.setString(3, newPost.coverPhotoUrl());
            pstmt.setString(4, newPost.type().dbValue());
            pstmt.setString(5, newPost.content());
            pstmt.setString(6, newPost.status().dbValue());
            pstmt.setInt(7, readingTimeMinutes);
            pstmt.setInt(8, newPost.viewCount() == null ? 0 : newPost.viewCount());
            pstmt.setTimestamp(9, parseNullableInstant(newPost.publishedAt()));
            pstmt.setTimestamp(10, parseNullableInstant(newPost.archivedAt()));

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(postFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error saving post");
        }
    }

    public void update(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();
        Post updatedPost = ctx.bodyAsClass(Post.class);
        int readingTimeMinutes = calculateReadingTimeMinutes(updatedPost.content());

        String sql = """
            UPDATE posts SET
                title = ?,
                description = ?,
                cover_photo_url = ?,
                type = ?::post_type,
                content = ?,
                status = ?::post_status,
                reading_time = ?,
                view_count = ?,
                published_at = ?,
                archived_at = ?
            WHERE id = ?
        """;

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, updatedPost.title() == null || updatedPost.title().isBlank() ? updatedPost.description() : updatedPost.title());
            pstmt.setString(2, updatedPost.description());
            pstmt.setString(3, updatedPost.coverPhotoUrl());
            pstmt.setString(4, updatedPost.type().dbValue());
            pstmt.setString(5, updatedPost.content());
            pstmt.setString(6, updatedPost.status().dbValue());
            pstmt.setInt(7, readingTimeMinutes);
            pstmt.setInt(8, updatedPost.viewCount() == null ? 0 : updatedPost.viewCount());
            pstmt.setTimestamp(9, parseNullableInstant(updatedPost.publishedAt()));
            pstmt.setTimestamp(10, parseNullableInstant(updatedPost.archivedAt()));
            pstmt.setObject(11, updatedPost.id());

            int rowsUpdated = pstmt.executeUpdate();

            if (rowsUpdated > 0) {
                ctx.status(200).json(updatedPost);
            } else {
                throw new NotFoundResponse("Post not found");
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error updating post");
        }
    }

    public void delete(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "DELETE FROM posts WHERE id = ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Post not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
