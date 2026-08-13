package com.jwpaisley.controllers;

import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.models.Recipe;
import com.jwpaisley.models.RecipeTag;
import com.jwpaisley.helpers.AuthHelper;
import com.jwpaisley.helpers.TimeHelper;
import io.javalin.http.Context;
import io.javalin.http.NotFoundResponse;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;

public class RecipeController {

    public static Recipe recipeFromResultSet(ResultSet rs, List<RecipeTag> recipeTags) throws SQLException {
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
            recipeTags,
            TimeHelper.toUtcIsoString(rs.getTimestamp("created_at")),
            TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"))
        );
    }

    private List<RecipeTag> loadRecipeTags(Connection conn, UUID recipeId) throws SQLException {
        List<RecipeTag> tags = new ArrayList<>();
        String sql = """
            SELECT rt.id, rt.name, rt.description, rt.created_at, rt.updated_at
            FROM public.recipe_tags rt
            INNER JOIN public.recipe_tag_mapping rtm ON rt.id = rtm.tag_id
            WHERE rtm.recipe_id = ?::uuid
            ORDER BY rt.name ASC
        """;

        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, recipeId);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    tags.add(RecipeTagController.recipeTagFromResultSet(rs));
                }
            }
        }

        return tags;
    }

    private void deleteRecipeTagMappingsForRecipe(Connection conn, UUID recipeId) throws SQLException {
        String sql = "DELETE FROM public.recipe_tag_mapping WHERE recipe_id = ?::uuid";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            pstmt.setObject(1, recipeId);
            pstmt.executeUpdate();
        }
    }

    private void syncRecipeTags(Connection conn, UUID recipeId, List<RecipeTag> recipeTags) throws SQLException {
        deleteRecipeTagMappingsForRecipe(conn, recipeId);

        if (recipeTags == null || recipeTags.isEmpty()) {
            return;
        }

        String sql = "INSERT INTO public.recipe_tag_mapping (recipe_id, tag_id) VALUES (?::uuid, ?::uuid)";
        try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
            for (RecipeTag tag : recipeTags) {
                if (tag == null || tag.id() == null) {
                    continue;
                }
                pstmt.setObject(1, recipeId);
                pstmt.setObject(2, tag.id());
                pstmt.executeUpdate();
            }
        }
    }

    private void handleError(Context ctx, Exception e) {
        System.err.println("Database Error: " + e.getMessage());
        ctx.status(500).result("Error accessing the recipe archive");
    }

    private List<UUID> parseRecipeTagIds(Context ctx) {
        List<String> rawValues = new ArrayList<>();
        String singleValue = ctx.queryParam("recipeTags");
        if (singleValue != null && !singleValue.isBlank()) {
            rawValues.add(singleValue);
        }
        rawValues.addAll(ctx.queryParams("recipeTags"));

        Set<UUID> tagIds = new LinkedHashSet<>();
        for (String rawValue : rawValues) {
            if (rawValue == null || rawValue.isBlank()) {
                continue;
            }

            for (String candidate : rawValue.split(",")) {
                String trimmed = candidate.trim();
                if (trimmed.isBlank()) {
                    continue;
                }

                try {
                    tagIds.add(UUID.fromString(trimmed));
                } catch (IllegalArgumentException ignored) {
                    System.err.println("Ignoring invalid recipe tag UUID: " + trimmed);
                }
            }
        }

        return new ArrayList<>(tagIds);
    }

    public void getAll(Context ctx) {
        List<Recipe> recipes = new ArrayList<>();
        int pageSize = 20;
        String pageToken = ctx.queryParam("pageToken");
        List<UUID> recipeTagIds = parseRecipeTagIds(ctx);
        DataSource ds = DatabaseService.getInstance().getDataSource();

        StringBuilder selectSql = new StringBuilder("SELECT * FROM public.recipes r");
        StringBuilder whereClause = new StringBuilder();
        List<Object> queryParams = new ArrayList<>();

        if (!recipeTagIds.isEmpty()) {
            whereClause.append(" WHERE ");
            for (int i = 0; i < recipeTagIds.size(); i++) {
                if (i > 0) {
                    whereClause.append(" AND ");
                }
                whereClause.append("EXISTS (SELECT 1 FROM public.recipe_tag_mapping rtm")
                    .append(i)
                    .append(" WHERE rtm")
                    .append(i)
                    .append(".recipe_id = r.id AND rtm")
                    .append(i)
                    .append(".tag_id = ?::uuid)");
                queryParams.add(recipeTagIds.get(i));
            }
        }

        String baseSql = selectSql.append(whereClause).toString();
        String sql = baseSql + " ORDER BY r.created_at DESC LIMIT ?";
        String sqlWithOffset = baseSql + " ORDER BY r.created_at DESC LIMIT ? OFFSET ?";

        try (Connection conn = ds.getConnection()) {
            int offset = 0;
            if (pageToken != null && !pageToken.isBlank()) {
                try {
                    offset = Integer.parseInt(pageToken) * pageSize;
                } catch (NumberFormatException ignored) {
                    offset = 0;
                }
            }

            try (PreparedStatement pstmt = conn.prepareStatement(offset > 0 ? sqlWithOffset : sql)) {
                int parameterIndex = 1;
                for (Object parameter : queryParams) {
                    pstmt.setObject(parameterIndex++, parameter);
                }

                if (offset > 0) {
                    pstmt.setInt(parameterIndex++, pageSize);
                    pstmt.setInt(parameterIndex, offset);
                } else {
                    pstmt.setInt(parameterIndex, pageSize);
                }

                try (ResultSet rs = pstmt.executeQuery()) {
                    while (rs.next()) {
                        UUID recipeId = rs.getObject("id", UUID.class);
                        recipes.add(recipeFromResultSet(rs, loadRecipeTags(conn, recipeId)));
                    }
                }
            }

            Map<String, Object> response = new HashMap<>();
            response.put("items", recipes);
            response.put("nextPageToken", recipes.size() == pageSize ? String.valueOf((offset / pageSize) + 1) : null);
            ctx.json(response);
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
                UUID recipeId = rs.getObject("id", UUID.class);
                ctx.json(recipeFromResultSet(rs, loadRecipeTags(conn, recipeId)));
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

        Connection conn = null;
        try {
            conn = ds.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
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
                pstmt.setArray(12, conn.createArrayOf("text", newRecipe.ingredients() == null ? new String[0] : newRecipe.ingredients().toArray()));
                pstmt.setArray(13, conn.createArrayOf("text", newRecipe.miseEnPlaceSteps() == null ? new String[0] : newRecipe.miseEnPlaceSteps().toArray()));
                pstmt.setArray(14, conn.createArrayOf("text", newRecipe.instructions() == null ? new String[0] : newRecipe.instructions().toArray()));

                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        UUID generatedId = rs.getObject("id", UUID.class);
                        String createdAt = TimeHelper.toUtcIsoString(rs.getTimestamp("created_at"));
                        String updatedAt = TimeHelper.toUtcIsoString(rs.getTimestamp("updated_at"));

                        syncRecipeTags(conn, generatedId, newRecipe.recipeTags());
                        conn.commit();

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
                            newRecipe.ingredients() == null ? List.of() : newRecipe.ingredients(),
                            newRecipe.miseEnPlaceSteps() == null ? List.of() : newRecipe.miseEnPlaceSteps(),
                            newRecipe.instructions() == null ? List.of() : newRecipe.instructions(),
                            newRecipe.recipeTags() == null ? List.of() : newRecipe.recipeTags(),
                            createdAt,
                            updatedAt
                        );

                        ctx.status(201).json(savedRecipe);
                    }
                }
            }
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackException) {
                    System.err.println("Rollback error: " + rollbackException.getMessage());
                }
            }
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error saving recipe to the archive");
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException closeException) {
                    System.err.println("Connection close error: " + closeException.getMessage());
                }
            }
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

        Connection conn = null;
        try {
            conn = ds.getConnection();
            conn.setAutoCommit(false);

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
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
                pstmt.setArray(12, conn.createArrayOf("text", updatedRecipe.ingredients() == null ? new String[0] : updatedRecipe.ingredients().toArray()));
                pstmt.setArray(13, conn.createArrayOf("text", updatedRecipe.miseEnPlaceSteps() == null ? new String[0] : updatedRecipe.miseEnPlaceSteps().toArray()));
                pstmt.setArray(14, conn.createArrayOf("text", updatedRecipe.instructions() == null ? new String[0] : updatedRecipe.instructions().toArray()));
                pstmt.setObject(15, updatedRecipe.id());

                int rowsUpdated = pstmt.executeUpdate();

                if (rowsUpdated > 0) {
                    syncRecipeTags(conn, updatedRecipe.id(), updatedRecipe.recipeTags());
                    conn.commit();
                    ctx.status(200).json(new Recipe(
                        updatedRecipe.id(),
                        updatedRecipe.name(),
                        updatedRecipe.description(),
                        updatedRecipe.emoji(),
                        updatedRecipe.servings(),
                        updatedRecipe.calories(),
                        updatedRecipe.protein(),
                        updatedRecipe.fat(),
                        updatedRecipe.carbohydrates(),
                        updatedRecipe.sugar(),
                        updatedRecipe.fiber(),
                        updatedRecipe.sodium(),
                        updatedRecipe.ingredients() == null ? List.of() : updatedRecipe.ingredients(),
                        updatedRecipe.miseEnPlaceSteps() == null ? List.of() : updatedRecipe.miseEnPlaceSteps(),
                        updatedRecipe.instructions() == null ? List.of() : updatedRecipe.instructions(),
                        updatedRecipe.recipeTags() == null ? List.of() : updatedRecipe.recipeTags(),
                        updatedRecipe.createdAt(),
                        updatedRecipe.updatedAt()
                    ));
                } else {
                    throw new NotFoundResponse("Recipe not found");
                }
            }
        } catch (SQLException e) {
            if (conn != null) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackException) {
                    System.err.println("Rollback error: " + rollbackException.getMessage());
                }
            }
            System.err.println("Database error: " + e.getMessage());
            ctx.status(500).result("Error updating recipe to the archive");
        } finally {
            if (conn != null) {
                try {
                    conn.close();
                } catch (SQLException closeException) {
                    System.err.println("Connection close error: " + closeException.getMessage());
                }
            }
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

        try (Connection conn = ds.getConnection()) {
            deleteRecipeTagMappingsForRecipe(conn, id);

            try (PreparedStatement pstmt = conn.prepareStatement(sql)) {
                pstmt.setObject(1, id);
                int rowsDeleted = pstmt.executeUpdate();

                if (rowsDeleted > 0) {
                    ctx.status(204);
                } else {
                    throw new NotFoundResponse("Recipe not found");
                }
            }
        } catch (SQLException e) {
            handleError(ctx, e);
        }
    }
}