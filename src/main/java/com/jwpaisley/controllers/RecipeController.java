package com.jwpaisley.controllers;

import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.models.Recipe;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.UUID;
import java.util.List;

public class RecipeController {

    public static Recipe recipeFromResultSet(ResultSet rs) throws SQLException {
        return new Recipe(
            rs.getObject("id", java.util.UUID.class),
            rs.getString("name"),
            rs.getString("description"),
            rs.getString("emoji"),
            rs.getInt("servings"),
            rs.getInt("calories"),
            rs.getInt("protein"),
            rs.getInt("fat"),
            rs.getInt("carbohydrates"),
            rs.getInt("sugar"),
            rs.getInt("fiber"),
            rs.getInt("sodium"),
            Arrays.asList((String[]) rs.getArray("ingredients").getArray()),
            Arrays.asList((String[]) rs.getArray("mise_en_place_steps").getArray()),
            Arrays.asList((String[]) rs.getArray("instructions").getArray())
        );
    }

    public void getAll(Context ctx) {
        List<Recipe> recipes = new ArrayList<>();
        String sql = "SELECT * FROM recipes ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql);
             ResultSet rs = pstmt.executeQuery()) {

            while (rs.next()) {
                recipes.add(recipeFromResultSet(rs));
            }
            ctx.json(recipes);
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    public void get(Context ctx) {
        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "SELECT * FROM recipes WHERE id = ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            ResultSet rs = pstmt.executeQuery();

            if (rs.next()) {
                ctx.json(recipeFromResultSet(rs));
            } else {
                throw new NotFoundResponse("Recipe not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing the recipe archive");
    }

    public void create(Context ctx) {
        ctx.status(201).result("Recipe added to the archive");
    }
}