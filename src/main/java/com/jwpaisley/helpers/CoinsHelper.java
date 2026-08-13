package com.jwpaisley.helpers;

import com.jwpaisley.services.DatabaseService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class CoinsHelper {
    private CoinsHelper() {
    }

    public static int addCoins(UUID userId, int delta) throws SQLException {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        String sql = "UPDATE users SET coins = coins + ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?::uuid RETURNING coins";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, delta);
            pstmt.setObject(2, userId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("coins");
                }
            }
        }

        throw new SQLException("Failed to update user coins for userId=" + userId);
    }

    public static int subtractCoins(UUID userId, int delta) throws SQLException {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        return addCoins(userId, -delta);
    }

    public static int setCoins(UUID userId, int totalCoins) throws SQLException {
        if (userId == null) {
            throw new IllegalArgumentException("userId is required");
        }

        String sql = "UPDATE users SET coins = ?, updated_at = CURRENT_TIMESTAMP WHERE id = ?::uuid RETURNING coins";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setInt(1, totalCoins);
            pstmt.setObject(2, userId);

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt("coins");
                }
            }
        }

        throw new SQLException("Failed to set user coins for userId=" + userId);
    }
}
