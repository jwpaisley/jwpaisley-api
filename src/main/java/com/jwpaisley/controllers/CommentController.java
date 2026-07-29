package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.TextHelper;
import com.jwpaisley.models.Comment;
import com.jwpaisley.models.CommentResourceType;
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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class CommentController {
    private static final int PAGE_SIZE = 10;

    public static Comment commentFromResultSet(ResultSet rs) throws SQLException {
        return new Comment(
            rs.getObject("id", UUID.class),
            rs.getObject("user_id", UUID.class),
            rs.getObject("resource_id", UUID.class),
            CommentResourceType.valueOf(rs.getString("type")),
            rs.getBoolean("is_reply"),
            rs.getObject("parent_comment", UUID.class),
            rs.getString("text"),
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing comments");
    }

    private int parsePageToken(String pageToken) {
        if (pageToken == null || pageToken.isBlank()) {
            return 0;
        }

        try {
            return Integer.parseInt(pageToken);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid page token");
        }
    }

    private UUID parseUuid(Object value) {
        if (value instanceof UUID uuid) {
            return uuid;
        }
        if (value instanceof String string && !string.isBlank()) {
            return UUID.fromString(string);
        }
        return null;
    }

    private String resolveUserDisplayName(Connection conn, UUID userId) throws SQLException {
        if (userId == null) {
            return "Someone";
        }

        String sql = "SELECT first_name, last_name FROM users WHERE id = ?::uuid";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, userId);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    String firstName = rs.getString("first_name");
                    String lastName = rs.getString("last_name");
                    String fullName = String.join(" ",
                        List.of(firstName != null ? firstName.trim() : "", lastName != null ? lastName.trim() : "").stream()
                            .filter(value -> !value.isBlank())
                            .toList());
                    return fullName.isBlank() ? "Someone" : fullName;
                }
            }
        }

        return "Someone";
    }

    private UUID resolveCollectionId(Connection conn, CommentResourceType commentType, UUID resourceId) throws SQLException {
        if (commentType == CommentResourceType.PHOTO_COLLECTION) {
            return resourceId;
        }

        if (commentType == CommentResourceType.PHOTO && resourceId != null) {
            String sql = "SELECT collection FROM photos WHERE id = ?::uuid";
            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setObject(1, resourceId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        return rs.getObject("collection", UUID.class);
                    }
                }
            }
        }

        return null;
    }

    private void sendCommentNotification(CommentResourceType commentType, UUID resourceId, UUID userId, UUID collectionId, Connection conn) {
        if (commentType != CommentResourceType.PHOTO_COLLECTION && commentType != CommentResourceType.PHOTO) {
            return;
        }

        if (userId == null) {
            return;
        }

        if (collectionId == null) {
            return;
        }

        try {
            String fullName = resolveUserDisplayName(conn, userId);
            String message;
            if (commentType == CommentResourceType.PHOTO_COLLECTION) {
                message = String.format(Locale.ROOT, "%s posted a new comment on a photo collection.\n\nhttps://jwpaisley.com/photography/collections/%s", fullName, collectionId);
            } else {
                message = String.format(Locale.ROOT, "%s posted a new comment on a photo.\n\nhttps://jwpaisley.com/photography/collections/%s?photo=%s", fullName, collectionId, resourceId);
            }

            TextHelper textHelper = new TextHelper();
            textHelper.sendSms(message, List.of(textHelper.paisleyPhoneNumber));
        } catch (Exception e) {
            System.err.println("Unable to send comment notification SMS: " + e.getMessage());
        }
    }

    public void create(Context ctx) {
        StringBuilder failureReason = new StringBuilder();
        if (!AuthHelper.validateOAuthToken(ctx, failureReason)) {
            ctx.status(401).result("Unauthorized: " + failureReason);
            return;
        }

        UUID currentUserId = AuthHelper.getCurrentUserId(ctx);
        if (currentUserId == null) {
            ctx.status(400).result("Invalid user context");
            return;
        }

        Map<String, Object> payload = ctx.bodyAsClass(Map.class);
        String text = payload.get("text") instanceof String textValue ? textValue : null;
        UUID resourceId = parseUuid(payload.get("resource"));
        if (resourceId == null) {
            resourceId = parseUuid(payload.get("resourceId"));
        }

        UUID parentCommentId = parseUuid(payload.get("parentComment"));
        if (parentCommentId == null) {
            parentCommentId = parseUuid(payload.get("parentCommentId"));
        }

        Boolean isReplyValue = payload.get("isReply") instanceof Boolean booleanValue ? booleanValue : false;
        String typeValue = payload.get("type") instanceof String typeString ? typeString : null;

        if (resourceId == null || typeValue == null || text == null || text.isBlank()) {
            ctx.status(400).result("Missing required comment fields");
            return;
        }

        CommentResourceType commentType;
        try {
            commentType = CommentResourceType.valueOf(typeValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.status(400).result("Invalid comment type");
            return;
        }

        boolean isReply = Boolean.TRUE.equals(isReplyValue);
        if (isReply) {
            if (parentCommentId == null) {
                ctx.status(400).result("Reply comments require a parent comment");
                return;
            }

            String parentCheckSql = "SELECT parent_comment FROM comments WHERE id = ?::uuid";
            DataSource ds = DatabaseService.getInstance().getDataSource();
            try (Connection conn = ds.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(parentCheckSql)) {
                pstmt.setObject(1, parentCommentId);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (!rs.next()) {
                        throw new NotFoundResponse("Parent comment not found");
                    }

                    UUID existingParentComment = rs.getObject("parent_comment", UUID.class);
                    if (existingParentComment != null) {
                        ctx.status(400).result("Cannot reply to a reply");
                        return;
                    }
                }
            } catch (SQLException e) {
                handleError(ctx, e);
                return;
            }
        }

        String sql = """
            INSERT INTO comments (user_id, resource_id, type, is_reply, parent_comment, text)
            VALUES (?::uuid, ?::uuid, ?, ?, ?::uuid, ?)
            RETURNING *;
        """;

        DataSource ds = DatabaseService.getInstance().getDataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, currentUserId);
            pstmt.setObject(2, resourceId);
            pstmt.setString(3, commentType.name());
            pstmt.setBoolean(4, isReply);
            pstmt.setObject(5, parentCommentId);
            pstmt.setString(6, text);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    Comment createdComment = commentFromResultSet(rs);
                    if (!isReply) {
                        UUID collectionId = resolveCollectionId(conn, commentType, resourceId);
                        sendCommentNotification(commentType, resourceId, currentUserId, collectionId, conn);
                    }
                    ctx.status(201).json(createdComment);
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM comments WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(commentFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Comment not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getAllForResource(Context ctx) {
        UUID resourceId = UUID.fromString(ctx.pathParam("resourceId"));
        String typeValue = ctx.queryParam("commentType");
        if (typeValue == null || typeValue.isBlank()) {
            ctx.status(400).result("Missing commentType query parameter");
            return;
        }

        int offset;
        try {
            offset = parsePageToken(ctx.queryParam("pageToken"));
        } catch (IllegalArgumentException e) {
            ctx.status(400).result(e.getMessage());
            return;
        }

        CommentResourceType commentType;
        try {
            commentType = CommentResourceType.valueOf(typeValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.status(400).result("Invalid comment type");
            return;
        }

        List<Comment> comments = new ArrayList<>();
        String sql = "SELECT * FROM comments WHERE resource_id = ?::uuid AND type = ? ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, resourceId);
            pstmt.setString(2, commentType.name());
            pstmt.setInt(3, PAGE_SIZE + 1);
            pstmt.setInt(4, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    comments.add(commentFromResultSet(rs));
                }
            }

            List<Comment> pageItems = comments.size() > PAGE_SIZE ? comments.subList(0, PAGE_SIZE) : comments;
            String nextPageToken = comments.size() > PAGE_SIZE ? String.valueOf(offset + PAGE_SIZE) : null;

            Map<String, Object> response = new HashMap<>();
            response.put("items", pageItems);
            response.put("nextPageToken", nextPageToken);
            ctx.json(response);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getRootComments(Context ctx) {
        UUID resourceId = UUID.fromString(ctx.pathParam("resourceId"));
        String typeValue = ctx.queryParam("commentType");
        if (typeValue == null || typeValue.isBlank()) {
            ctx.status(400).result("Missing commentType query parameter");
            return;
        }

        int offset;
        try {
            offset = parsePageToken(ctx.queryParam("pageToken"));
        } catch (IllegalArgumentException e) {
            ctx.status(400).result(e.getMessage());
            return;
        }

        CommentResourceType commentType;
        try {
            commentType = CommentResourceType.valueOf(typeValue.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            ctx.status(400).result("Invalid comment type");
            return;
        }

        List<Comment> comments = new ArrayList<>();
        String sql = "SELECT * FROM comments WHERE resource_id = ?::uuid AND type = ? AND is_reply = false AND parent_comment IS NULL ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, resourceId);
            pstmt.setString(2, commentType.name());
            pstmt.setInt(3, PAGE_SIZE + 1);
            pstmt.setInt(4, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    comments.add(commentFromResultSet(rs));
                }
            }

            List<Comment> pageItems = comments.size() > PAGE_SIZE ? comments.subList(0, PAGE_SIZE) : comments;
            String nextPageToken = comments.size() > PAGE_SIZE ? String.valueOf(offset + PAGE_SIZE) : null;

            Map<String, Object> response = new HashMap<>();
            response.put("items", pageItems);
            response.put("nextPageToken", nextPageToken);
            ctx.json(response);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void getReplies(Context ctx) {
        UUID parentCommentId = UUID.fromString(ctx.pathParam("parentCommentId"));
        int offset;
        try {
            offset = parsePageToken(ctx.queryParam("pageToken"));
        } catch (IllegalArgumentException e) {
            ctx.status(400).result(e.getMessage());
            return;
        }

        List<Comment> comments = new ArrayList<>();
        String sql = "SELECT * FROM comments WHERE parent_comment = ?::uuid ORDER BY created_at ASC, id ASC LIMIT ? OFFSET ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, parentCommentId);
            pstmt.setInt(2, PAGE_SIZE + 1);
            pstmt.setInt(3, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    comments.add(commentFromResultSet(rs));
                }
            }

            List<Comment> pageItems = comments.size() > PAGE_SIZE ? comments.subList(0, PAGE_SIZE) : comments;
            String nextPageToken = comments.size() > PAGE_SIZE ? String.valueOf(offset + PAGE_SIZE) : null;

            Map<String, Object> response = new HashMap<>();
            response.put("items", pageItems);
            response.put("nextPageToken", nextPageToken);
            ctx.json(response);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void updateComment(Context ctx) {
        StringBuilder failureReason = new StringBuilder();
        if (!AuthHelper.validateOAuthToken(ctx, failureReason)) {
            ctx.status(401).result("Unauthorized: " + failureReason);
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        UUID currentUserId = AuthHelper.getCurrentUserId(ctx);
        if (currentUserId == null) {
            ctx.status(400).result("Invalid user context");
            return;
        }

        Map<String, Object> payload = ctx.bodyAsClass(Map.class);
        String newText = payload.get("text") instanceof String textValue ? textValue : null;
        if (newText == null || newText.isBlank()) {
            ctx.status(400).result("Comment text is required");
            return;
        }

        String existingSql = "SELECT user_id FROM comments WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement selectStmt = conn.prepareStatement(existingSql)) {
            selectStmt.setObject(1, id);
            try (ResultSet rs = selectStmt.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundResponse("Comment not found");
                }

                UUID commentOwnerId = rs.getObject("user_id", UUID.class);
                if (!AuthHelper.isAdmin(ctx) && !currentUserId.equals(commentOwnerId)) {
                    ctx.status(403).result("Forbidden");
                    return;
                }
            }

            String updateSql = """
                UPDATE comments
                SET text = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?::uuid
                RETURNING *;
            """;

            try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                updateStmt.setString(1, newText);
                updateStmt.setObject(2, id);
                try (ResultSet updateRs = updateStmt.executeQuery()) {
                    if (updateRs.next()) {
                        ctx.json(commentFromResultSet(updateRs));
                    }
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void deleteComment(Context ctx) {
        StringBuilder failureReason = new StringBuilder();
        if (!AuthHelper.validateOAuthToken(ctx, failureReason)) {
            ctx.status(401).result("Unauthorized: " + failureReason);
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        UUID currentUserId = AuthHelper.getCurrentUserId(ctx);
        if (currentUserId == null) {
            ctx.status(400).result("Invalid user context");
            return;
        }

        String existingSql = "SELECT user_id FROM comments WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement selectStmt = conn.prepareStatement(existingSql)) {
            selectStmt.setObject(1, id);
            try (ResultSet rs = selectStmt.executeQuery()) {
                if (!rs.next()) {
                    throw new NotFoundResponse("Comment not found");
                }

                UUID commentOwnerId = rs.getObject("user_id", UUID.class);
                if (!AuthHelper.isAdmin(ctx) && !currentUserId.equals(commentOwnerId)) {
                    ctx.status(403).result("Forbidden");
                    return;
                }
            }

            String softDeleteSql = """
                UPDATE comments
                SET text = ?, updated_at = CURRENT_TIMESTAMP
                WHERE id = ?::uuid
                RETURNING *;
            """;
            try (PreparedStatement updateStmt = conn.prepareStatement(softDeleteSql)) {
                updateStmt.setString(1, "deleted comment");
                updateStmt.setObject(2, id);
                try (ResultSet updateRs = updateStmt.executeQuery()) {
                    if (!updateRs.next()) {
                        throw new NotFoundResponse("Comment not found");
                    }

                    String deleteRepliesSql = "DELETE FROM comments WHERE parent_comment = ?::uuid";
                    try (PreparedStatement deleteRepliesStmt = conn.prepareStatement(deleteRepliesSql)) {
                        deleteRepliesStmt.setObject(1, id);
                        deleteRepliesStmt.executeUpdate();
                    }

                    ctx.json(commentFromResultSet(updateRs));
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
