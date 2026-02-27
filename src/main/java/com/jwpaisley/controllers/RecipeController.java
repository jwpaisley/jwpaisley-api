package com.jwpaisley.controllers;

import com.jwpaisley.services.DatabaseService;
import com.jwpaisley.models.Recipe;

import io.javalin.http.Context;
import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class RecipeController {
    public void getAll(Context ctx) {
        List<Recipe> recipes = new ArrayList<>();
        String sql = "SELECT * FROM recipes ORDER BY created_at DESC";
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            PreparedStatement pstmt = conn.prepareStatement(sql);
            ResultSet rs = pstmt.executeQuery();

            while (rs.next()) {
                Recipe recipe = new Recipe(
                    rs.getInt("id"),
                    rs.getString("name"),
                    rs.getString("description"),
                    rs.getString("emoji"),

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
                
                recipes.add(recipe);
            }
        } catch (Exception e) {
            System.out.println("Error fetching recipes: " + e.getMessage());
            ctx.status(500).result("Something went wrong!");
        }

        ctx.json(recipes);
    }

    public void create(Context ctx) {
        ctx.status(201).result("stew added to the archive");
    }
}