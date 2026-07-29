package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.JwtHelper;
import com.jwpaisley.models.User;
import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.services.StorageService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import io.javalin.http.UploadedFile;

import javax.sql.DataSource;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLConnection;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public class UserController {
    private static final int PAGE_SIZE = 20;
    private static final String PROFILE_BUCKET = "jwpaisley-user-profile-pictures";
    private final JwtHelper jwtService = new JwtHelper(System.getenv().getOrDefault("JWT_SECRET", "dummy-secret-for-dev"));

    public static User userFromResultSet(ResultSet rs) throws SQLException {
        return new User(
            rs.getObject("id", UUID.class),
            rs.getString("first_name"),
            rs.getString("last_name"),
            rs.getString("email_address"),
            rs.getString("profile_picture_url"),
            rs.getObject("coins", Integer.class),
            rs.getTimestamp("last_login") != null ? rs.getTimestamp("last_login").toString() : null,
            rs.getTimestamp("created_at") != null ? rs.getTimestamp("created_at").toString() : null,
            rs.getTimestamp("updated_at") != null ? rs.getTimestamp("updated_at").toString() : null
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing users");
    }

    public void getAll(Context ctx) {
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

        List<User> users = new ArrayList<>();
        String sql = "SELECT * FROM users ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, PAGE_SIZE + 1);
            pstmt.setInt(2, offset);

            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    users.add(userFromResultSet(rs));
                }
            }

            List<User> pageItems = users.size() > PAGE_SIZE ? users.subList(0, PAGE_SIZE) : users;
            String nextPageToken = users.size() > PAGE_SIZE ? String.valueOf(offset + PAGE_SIZE) : null;

            Map<String, Object> response = new HashMap<>();
            response.put("items", pageItems);
            response.put("nextPageToken", nextPageToken);
            ctx.json(response);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM users WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(userFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("User not found");
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

        User newUser = ctx.bodyAsClass(User.class);
        String sql = """
            INSERT INTO users (first_name, last_name, email_address, profile_picture_url)
            VALUES (?, ?, ?, ?)
            RETURNING *;
        """;

        DataSource ds = DatabaseService.getInstance().getDataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, newUser.firstName());
            pstmt.setString(2, newUser.lastName());
            pstmt.setString(3, newUser.emailAddress());
            pstmt.setString(4, newUser.profilePictureUrl());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(userFromResultSet(rs));
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void update(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        UUID currentUserId = AuthHelper.getCurrentUserId(ctx);
        if (!AuthHelper.isAdmin(ctx) && (currentUserId == null || !currentUserId.equals(id))) {
            ctx.status(403).result("Forbidden");
            return;
        }

        User updatedUser = ctx.bodyAsClass(User.class);
        String sql = """
            UPDATE users
            SET first_name = ?, last_name = ?, email_address = ?, profile_picture_url = ?
            WHERE id = ?::uuid
            RETURNING *;
        """;

        DataSource ds = DatabaseService.getInstance().getDataSource();
        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setString(1, updatedUser.firstName());
            pstmt.setString(2, updatedUser.lastName());
            pstmt.setString(3, updatedUser.emailAddress());
            pstmt.setString(4, updatedUser.profilePictureUrl());
            pstmt.setObject(5, id);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(200).json(userFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("User not found");
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
        String sql = "DELETE FROM users WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();
            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("User not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void uploadProfilePic(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID currentUserId = AuthHelper.getCurrentUserId(ctx);
        if (currentUserId == null) {
            ctx.status(400).result("Invalid user context");
            return;
        }

        StorageService storageService = StorageService.getInstance();
        try {
            UploadedFile file = ctx.uploadedFile("profilePic");
            if (file == null) {
                ctx.status(400).result("No file uploaded");
                return;
            }

            String uploadedUrl = storageService.uploadFile(file, PROFILE_BUCKET);
            String sql = "UPDATE users SET profile_picture_url = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?::uuid RETURNING *";
            DataSource ds = DatabaseService.getInstance().getDataSource();

            try (Connection conn = ds.getConnection();
                 PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setString(1, uploadedUrl);
                pstmt.setObject(2, currentUserId);

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        ctx.json(Map.of("url", uploadedUrl, "user", userFromResultSet(rs)));
                    } else {
                        throw new NotFoundResponse("User not found");
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("Storage error: " + e.getMessage());
            ctx.status(500).result("Error uploading profile picture");
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void login(Context ctx) {
        Map<String, Object> loginPayload = ctx.bodyAsClass(Map.class);
        String credential = (String) loginPayload.get("credential");

        Map<String, String> profile = credential != null && !credential.isBlank()
            ? decodeGoogleCredential(credential)
            : null;

        String email = profile != null ? profile.get("email") : (String) loginPayload.get("email");
        String firstName = profile != null ? profile.get("firstName") : (String) loginPayload.get("firstName");
        String lastName = profile != null ? profile.get("lastName") : (String) loginPayload.get("lastName");
        String profilePictureUrl = profile != null ? profile.get("profilePictureUrl") : (String) loginPayload.get("profilePictureUrl");

        if (email == null || email.isBlank()) {
            ctx.status(400).result("Email is required");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();
        String selectSql = "SELECT * FROM users WHERE email_address = ?";
        String insertSql = """
            INSERT INTO users (first_name, last_name, email_address, profile_picture_url, last_login)
            VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)
            RETURNING *;
        """;
        String updateSql = "UPDATE users SET last_login = CURRENT_TIMESTAMP, profile_picture_url = COALESCE(?, profile_picture_url), updated_at = CURRENT_TIMESTAMP WHERE email_address = ? RETURNING *";

        try (Connection conn = ds.getConnection()) {
            User existingUser = null;
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                selectStmt.setString(1, email);
                try (ResultSet rs = selectStmt.executeQuery()) {
                    if (rs.next()) {
                        existingUser = userFromResultSet(rs);
                    }
                }
            }

            String resolvedProfilePictureUrl = existingUser != null && existingUser.profilePictureUrl() != null
                ? existingUser.profilePictureUrl()
                : null;

            if (profilePictureUrl != null && !profilePictureUrl.isBlank()) {
                if (existingUser == null || shouldUploadProfilePicture(existingUser.profilePictureUrl())) {
                    resolvedProfilePictureUrl = uploadProfilePicture(profilePictureUrl);
                }
            }

            if (existingUser == null) {
                try (PreparedStatement insertStmt = conn.prepareStatement(insertSql)) {
                    insertStmt.setString(1, firstName != null ? firstName : "");
                    insertStmt.setString(2, lastName != null ? lastName : "");
                    insertStmt.setString(3, email);
                    insertStmt.setString(4, resolvedProfilePictureUrl);
                    try (ResultSet rs = insertStmt.executeQuery()) {
                        if (rs.next()) {
                            existingUser = userFromResultSet(rs);
                        }
                    }
                }
            } else {
                try (PreparedStatement updateStmt = conn.prepareStatement(updateSql)) {
                    updateStmt.setString(1, resolvedProfilePictureUrl);
                    updateStmt.setString(2, email);
                    try (ResultSet rs = updateStmt.executeQuery()) {
                        if (rs.next()) {
                            existingUser = userFromResultSet(rs);
                        }
                    }
                }
            }

            if (existingUser == null) {
                ctx.status(500).result("Unable to process login");
                return;
            }

            boolean isAdmin = email.equalsIgnoreCase("jacobpaisley97@gmail.com");
            Map<String, Object> claims = new HashMap<>();
            claims.put("userId", existingUser.id().toString());
            claims.put("email", existingUser.emailAddress());
            claims.put("firstName", existingUser.firstName());
            claims.put("lastName", existingUser.lastName());
            claims.put("coins", existingUser.coins());
            claims.put("isAdmin", isAdmin);
            String token = jwtService.createToken(claims, Duration.ofDays(7));

            Map<String, Object> response = new HashMap<>();
            response.put("token", token);
            response.put("user", existingUser);
            response.put("isAdmin", isAdmin);
            ctx.json(response);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    private Map<String, String> decodeGoogleCredential(String credential) {
        try {
            String[] parts = credential.split("\\.");
            if (parts.length < 2) {
                return null;
            }

            String payload = parts[1];
            String padded = payload + "=".repeat(4 - (payload.length() % 4) % 4);
            String decoded = new String(java.util.Base64.getUrlDecoder().decode(padded), java.nio.charset.StandardCharsets.UTF_8);
            Map<String, Object> parsed = new com.fasterxml.jackson.databind.ObjectMapper().readValue(decoded, Map.class);

            Map<String, String> profile = new HashMap<>();
            profile.put("email", (String) parsed.get("email"));
            profile.put("firstName", (String) parsed.get("given_name"));
            profile.put("lastName", (String) parsed.get("family_name"));
            profile.put("profilePictureUrl", (String) parsed.get("picture"));
            return profile;
        } catch (Exception e) {
            System.err.println("Unable to decode Google credential: " + e.getMessage());
            return null;
        }
    }

    private String uploadProfilePicture(String profilePictureUrl) {
        try {
            URLConnection connection = new URL(profilePictureUrl).openConnection();
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);
            try (InputStream inputStream = connection.getInputStream();
                 ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    outputStream.write(buffer, 0, read);
                }

                String contentType = connection.getContentType();
                return StorageService.getInstance().uploadByteArray(outputStream.toByteArray(), contentType, PROFILE_BUCKET);
            }
        } catch (Exception e) {
            System.err.println("Unable to upload profile picture: " + e.getMessage());
            return profilePictureUrl;
        }
    }

    static boolean shouldUploadProfilePicture(String profilePictureUrl) {
        if (profilePictureUrl == null || profilePictureUrl.isBlank()) {
            return false;
        }

        String normalizedUrl = profilePictureUrl.toLowerCase(Locale.ROOT);
        return !normalizedUrl.contains("storage.googleapis.com");
    }
}
