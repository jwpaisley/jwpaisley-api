package com.jwpaisley;

import io.javalin.Javalin;

import com.jwpaisley.controllers.RecipeController;

public class Main {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    it.allowHost("https://jwpaisley.com", "http://localhost:4200");
                });
            });
        }).start(port);

        // RECIPES ROUTES
        RecipeController recipeController = new RecipeController();

        app.get("/api/recipes", recipeController::getAll);
        app.post("/api/recipes", recipeController::create);
    }
}

record PingResponse(String message, int status) {}
