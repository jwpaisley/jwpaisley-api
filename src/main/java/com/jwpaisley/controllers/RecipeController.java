package com.jwpaisley.controllers;

import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.models.Recipe;
import com.jwpaisley.helpers.AuthHelper;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
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
            Arrays.asList((String[]) rs.getArray("instructions").getArray()),
            rs.getTimestamp("created_at").toString(),
            rs.getTimestamp("updated_at").toString()
        );
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing the recipe archive");
    }

    public void getAll(Context ctx) {
        List<Recipe> recipes = new ArrayList<>();
        String sql = "SELECT * FROM public.recipes ORDER BY created_at DESC";
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
        String sql = "SELECT * FROM public.recipes WHERE id = ?::uuid";
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

    public void create(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();
        Recipe newRecipe = ctx.bodyAsClass(Recipe.class);

        String sql = """
            INSERT INTO public.recipes (
                name, description, emoji, servings, calories, 
                protein, fat, carbohydrates, sugar, fiber, sodium,
                ingredients, mise_en_place_steps, instructions
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            RETURNING *;
        """;

        try (Connection conn = ds.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, newRecipe.name());
            pstmt.setString(2, newRecipe.description());
            pstmt.setString(3, newRecipe.emoji());
            pstmt.setInt(4, newRecipe.servings());
            pstmt.setInt(5, newRecipe.calories());
            pstmt.setInt(6, newRecipe.protein());
            pstmt.setInt(7, newRecipe.fat());
            pstmt.setInt(8, newRecipe.carbohydrates());
            pstmt.setInt(9, newRecipe.sugar());
            pstmt.setInt(10, newRecipe.fiber());
            pstmt.setInt(11, newRecipe.sodium());
            pstmt.setArray(12, conn.createArrayOf("text", newRecipe.ingredients().toArray()));
            pstmt.setArray(13, conn.createArrayOf("text", newRecipe.miseEnPlaceSteps().toArray()));
            pstmt.setArray(14, conn.createArrayOf("text", newRecipe.instructions().toArray()));

            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    UUID generatedId = rs.getObject("id", UUID.class);
                    String createdAt = rs.getTimestamp("created_at").toString();
                    String updatedAt = rs.getTimestamp("updated_at").toString();

                    Recipe savedRecipe = new Recipe(
                        generatedId,
                        newRecipe.name(),
                        newRecipe.description(),
                        newRecipe.emoji(),
                        newRecipe.servings(),
                        newRecipe.calories(),
                        newRecipe.protein(),
                        newRecipe.fat(),
                        newRecipe.carbohydrates(),
                        newRecipe.sugar(),
                        newRecipe.fiber(),
                        newRecipe.sodium(),
                        newRecipe.ingredients(),
                        newRecipe.miseEnPlaceSteps(),
                        newRecipe.instructions(),
                        createdAt,
                        updatedAt
                    );

                    ctx.status(201).json(savedRecipe);
                }
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error saving recipe to the archive");
        }
    }

    public void update(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        DataSource ds = DatabaseService.getInstance().getDataSource();
        Recipe updatedRecipe = ctx.bodyAsClass(Recipe.class);

        String sql = """
            UPDATE public.recipes SET 
                name = ?, description = ?, emoji = ?, servings = ?, 
                calories = ?, protein = ?, fat = ?, carbohydrates = ?, 
                sugar = ?, fiber = ?, sodium = ?, ingredients = ?, 
                mise_en_place_steps = ?, instructions = ?
            WHERE id = ?
        """;

        try (Connection conn = ds.getConnection();
            PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setString(1, updatedRecipe.name());
            pstmt.setString(2, updatedRecipe.description());
            pstmt.setString(3, updatedRecipe.emoji());
            pstmt.setInt(4, updatedRecipe.servings());
            pstmt.setInt(5, updatedRecipe.calories());
            pstmt.setInt(6, updatedRecipe.protein());
            pstmt.setInt(7, updatedRecipe.fat());
            pstmt.setInt(8, updatedRecipe.carbohydrates());
            pstmt.setInt(9, updatedRecipe.sugar());
            pstmt.setInt(10, updatedRecipe.fiber());
            pstmt.setInt(11, updatedRecipe.sodium());
            pstmt.setArray(12, conn.createArrayOf("text", updatedRecipe.ingredients().toArray()));
            pstmt.setArray(13, conn.createArrayOf("text", updatedRecipe.miseEnPlaceSteps().toArray()));
            pstmt.setArray(14, conn.createArrayOf("text", updatedRecipe.instructions().toArray()));
            pstmt.setObject(15, updatedRecipe.id());

            int rowsUpdated = pstmt.executeUpdate();

            if (rowsUpdated > 0) {
                ctx.status(200).json(updatedRecipe);
            } else {
                throw new NotFoundResponse("Recipe not found");
            }
        } catch (SQLException e) {
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error updating recipe to the archive");
        }
    }

    public void delete(Context ctx) {
        if (!AuthHelper.validateOAuthToken(ctx) || !AuthHelper.isAdmin(ctx)) {
            ctx.status(401).result("Unauthorized");
            return;
        }

        UUID id = UUID.fromString(ctx.pathParam("id"));
        String sql = "DELETE FROM public.recipes WHERE id = ?";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(sql)) {

            pstmt.setObject(1, id);
            int rowsDeleted = pstmt.executeUpdate();

            if (rowsDeleted > 0) {
                ctx.status(204);
            } else {
                throw new NotFoundResponse("Recipe not found");
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}