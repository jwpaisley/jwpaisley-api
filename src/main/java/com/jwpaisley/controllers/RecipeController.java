package com.jwpaisley.controllers;

import com.jwpaisley.services.DatabaseService;
import io.javalin.http.Context;
import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;

public class RecipeController {
    public void getAll(Context ctx) {
        DataSource ds = DatabaseService.getInstance().getDataSource();

        try (Connection conn = ds.getConnection()) {
            ctx.json(List.of("dry bean stew", "mushroom bisque", "herb salad"));
        } catch (Exception e) {
            ctx.status(500).result("The cauldron spilled: " + e.getMessage());
        }
    }

    public void create(Context ctx) {
        ctx.status(201).result("stew added to the archive");
    }
}