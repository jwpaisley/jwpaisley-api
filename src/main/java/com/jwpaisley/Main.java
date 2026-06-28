package com.jwpaisley;

import io.javalin.Javalin;

import com.jwpaisley.controllers.BooksController;
import com.jwpaisley.controllers.RecipeController;

public class Main {
    public static void main(String[] args) {
        int port = Integer.parseInt(System.getenv().getOrDefault("PORT", "8080"));

        Javalin app = Javalin.create(config -> {
            config.bundledPlugins.enableCors(cors -> {
                cors.addRule(it -> {
                    it.allowHost("https://jwpaisley.com", "http://localhost:4200");
                    it.allowCredentials = true;
                });
            });
        }).start(port);

        // HEALTH CHECK
        app.get("/api/ping", ctx -> {
            ctx.json(new PingResponse("pong", 200));
        });

        // BOOKS ROUTES
        BooksController booksController = new BooksController();
        app.get("/api/books", booksController::getAll);
        app.get("/api/books/{id}", booksController::get);
        app.post("/api/books", booksController::create);
        app.put("/api/books/{id}", booksController::update);
        app.delete("/api/books/{id}", booksController::delete);
        app.post("/api/book-covers", booksController::uploadCover);

        // RECIPES ROUTES
        RecipeController recipeController = new RecipeController();

        app.get("/api/recipes", recipeController::getAll);
        app.get("/api/recipes/{id}", recipeController::get);
        app.post("/api/recipes", recipeController::create);
        app.put("/api/recipes/{id}", recipeController::update);
        app.delete("/api/recipes/{id}", recipeController::delete);
    }
}

record PingResponse(String message, int status) {}
