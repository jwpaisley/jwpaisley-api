package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.TimeHelper;
import com.jwpaisley.models.RecipeTag;
import com.jwpaisley.services.DatabaseService;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class RecipeTagController {

    public static RecipeTag recipeTagFromResultSet(ResultSet rs) throws SQLException {
        return new RecipeTag(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("description"),
            TimeHelper.toUtcIsoString(rs.getTimestamp("created_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing recipe tags");
    }

    public void getAll(Context ctx) {
        List<RecipeTag> tags = new ArrayList<>();
        String sql = "SELECT * FROM public.recipe_tags ORDER BY name ASC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                tags.add(recipeTagFromResultSet(rs));
            }

            ctx.json(tags);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM public.recipe_tags WHERE id = ?::uuid";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.json(recipeTagFromResultSet(rs));
                } else {
                    throw new NotFoundResponse("Recipe tag not found");
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

        RecipeTag newTag = ctx.bodyAsClass(RecipeTag.class);
        String sql = "INSERT INTO public.recipe_tags (name, description) VALUES (?, ?) RETURNING *";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newTag.name());
            pstmt.setString(2, newTag.description());

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    ctx.status(201).json(recipeTagFromResultSet(rs));
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

        UUID id = UUID.fromString(ctx.pathParam("id"));
        RecipeTag updatedTag = ctx.bodyAsClass(RecipeTag.class);
        String sql = "UPDATE public.recipe_tags SET name = ?, description = ? WHERE id = ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, updatedTag.name());
            pstmt.setString(2, updatedTag.description());
            pstmt.setObject(3, id);

            int rowsUpdated = pstmt.executeUpdate();
            if (rowsUpdated > 0) {
                ctx.status(200).json(updatedTag);
            } else {
                throw new NotFoundResponse("Recipe tag not found");
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
        String sql = "DELETE FROM public.recipe_tags WHERE id = ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            String mappingSql = "DELETE FROM public.recipe_tag_mapping WHERE tag_id = ?::uuid";
            try (PreparedStatement mappingPstmt = conn.prepareStatement(mappingSql)) {
                mappingPstmt.setObject(1, id);
                mappingPstmt.executeUpdate();
            }

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setObject(1, id);
                int rowsDeleted = pstmt.executeUpdate();
                if (rowsDeleted > 0) {
                    ctx.status(204);
                } else {
                    throw new NotFoundResponse("Recipe tag not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}
