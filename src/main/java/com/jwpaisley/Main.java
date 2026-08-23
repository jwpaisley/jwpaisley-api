package com.jwpaisley;

import io.javalin.Javalin;

import com.jwpaisley.helpers.LoggingHelper;
import com.jwpaisley.controllers.BooksController;
import com.jwpaisley.controllers.CommentController;
import com.jwpaisley.controllers.PhotoCollectionController;
import com.jwpaisley.controllers.PhotoController;
import com.jwpaisley.controllers.RecipeController;
import com.jwpaisley.controllers.RecipeTagController;
import com.jwpaisley.controllers.SailingPortsController;
import com.jwpaisley.controllers.SailboatPhotosController;
import com.jwpaisley.controllers.SailboatsController;
import com.jwpaisley.controllers.SportsPredictionFixturesController;
import com.jwpaisley.controllers.SportsPredictionLeagueParticipantsController;
import com.jwpaisley.controllers.SportsPredictionLeaguesController;
import com.jwpaisley.controllers.SportsPredictionPicksController;
import com.jwpaisley.controllers.SportsPredictionTeamsController;
import com.jwpaisley.controllers.UserController;
import com.jwpaisley.controllers.WheelOptionsController;
import com.jwpaisley.controllers.WheelSpinsController;

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

        // PHOTO COLLECTION ROUTES
        PhotoCollectionController photoCollectionController = new PhotoCollectionController();
        app.get("/api/photo-collections", photoCollectionController::getAll);
        app.get("/api/photo-collections/{id}", photoCollectionController::get);
        app.get("/api/photo-collections/{id}/photos", photoCollectionController::getPhotosForCollection);
        app.post("/api/photo-collections", photoCollectionController::create);
        app.put("/api/photo-collections/{id}", photoCollectionController::update);
        app.delete("/api/photo-collections/{id}", photoCollectionController::delete);

        // PHOTO ROUTES
        PhotoController photoController = new PhotoController();
        app.get("/api/photos", photoController::getAll);
        app.get("/api/photos/collection/{collectionId}", photoController::getByCollection);
        app.get("/api/photos/{id}", photoController::get);
        app.post("/api/photos", photoController::create);
        app.put("/api/photos/{id}", photoController::update);
        app.delete("/api/photos/{id}", photoController::delete);
        app.post("/api/photos/upload", photoController::uploadPhoto);

        // AUTH / USER ROUTES
        UserController userController = new UserController();
        app.post("/api/auth/login", userController::login);
        app.get("/api/users", userController::getAll);
        app.get("/api/users/{id}", userController::get);
        app.post("/api/users", userController::create);
        app.put("/api/users/{id}", userController::update);
        app.delete("/api/users/{id}", userController::delete);
        app.post("/api/users/profile-pic", userController::uploadProfilePic);

        // COMMENTS ROUTES
        CommentController commentController = new CommentController();
        app.post("/api/comments", commentController::create);
        app.get("/api/comments/{id}", commentController::get);
        app.get("/api/comments/resource/{resourceId}", commentController::getAllForResource);
        app.get("/api/comments/root/{resourceId}", commentController::getRootComments);
        app.get("/api/comments/replies/{parentCommentId}", commentController::getReplies);
        app.put("/api/comments/{id}", commentController::updateComment);
        app.delete("/api/comments/{id}", commentController::deleteComment);

        // RECIPES ROUTES
        RecipeController recipeController = new RecipeController();
        app.get("/api/recipes", recipeController::getAll);
        app.get("/api/recipes/{id}", recipeController::get);
        app.post("/api/recipes", recipeController::create);
        app.put("/api/recipes/{id}", recipeController::update);
        app.delete("/api/recipes/{id}", recipeController::delete);

        RecipeTagController recipeTagController = new RecipeTagController();
        app.get("/api/recipe-tags", recipeTagController::getAll);
        app.get("/api/recipe-tags/{id}", recipeTagController::get);
        app.post("/api/recipe-tags", recipeTagController::create);
        app.put("/api/recipe-tags/{id}", recipeTagController::update);
        app.delete("/api/recipe-tags/{id}", recipeTagController::delete);

        SailingPortsController sailingPortsController = new SailingPortsController();
        app.get("/api/sailing-ports", sailingPortsController::getAll);
        app.get("/api/sailing-ports/{id}", sailingPortsController::get);
        app.get("/api/sailing-ports/{id}/conditions-history", sailingPortsController::getHistory);
        app.post("/api/sailing-ports/fetch-conditions-job", sailingPortsController::fetchConditionsJob);
        app.post("/api/sailing-ports", sailingPortsController::create);
        app.put("/api/sailing-ports/{id}", sailingPortsController::update);
        app.delete("/api/sailing-ports/{id}", sailingPortsController::delete);

        SailboatsController sailboatsController = new SailboatsController();
        app.get("/api/sailboats", sailboatsController::getAll);
        app.get("/api/sailboats/{id}", sailboatsController::get);
        app.post("/api/sailboats", sailboatsController::create);
        app.put("/api/sailboats/{id}", sailboatsController::update);
        app.delete("/api/sailboats/{id}", sailboatsController::delete);

        SailboatPhotosController sailboatPhotosController = new SailboatPhotosController();
        app.get("/api/sailboat-photos", sailboatPhotosController::getAll);
        app.get("/api/sailboats/{sailboatId}/photos", sailboatPhotosController::getBySailboat);
        app.get("/api/sailboat-photos/{id}", sailboatPhotosController::get);
        app.post("/api/sailboat-photos", sailboatPhotosController::create);
        app.put("/api/sailboat-photos/{id}", sailboatPhotosController::update);
        app.delete("/api/sailboat-photos/{id}", sailboatPhotosController::delete);
        app.post("/api/sailboat-photos/upload", sailboatPhotosController::uploadPhoto);

        SportsPredictionTeamsController sportsPredictionTeamsController = new SportsPredictionTeamsController();
        app.get("/api/sports-prediction-teams", sportsPredictionTeamsController::getAll);
        app.get("/api/sports-prediction-teams/league/{leagueId}", sportsPredictionTeamsController::getForLeague);
        app.get("/api/sports-prediction-teams/{id}", sportsPredictionTeamsController::get);
        app.post("/api/sports-prediction-teams", sportsPredictionTeamsController::create);
        app.put("/api/sports-prediction-teams/{id}", sportsPredictionTeamsController::update);
        app.delete("/api/sports-prediction-teams/{id}", sportsPredictionTeamsController::delete);

        SportsPredictionLeaguesController sportsPredictionLeaguesController = new SportsPredictionLeaguesController();
        app.get("/api/sports-prediction-leagues", sportsPredictionLeaguesController::getAll);
        app.get("/api/sports-prediction-leagues/my", sportsPredictionLeaguesController::getMyLeagues);
        app.get("/api/sports-prediction-leagues/past", sportsPredictionLeaguesController::getPastLeagues);
        app.get("/api/sports-prediction-leagues/open", sportsPredictionLeaguesController::getOpenLeagues);
        app.get("/api/sports-prediction-leagues/{id}", sportsPredictionLeaguesController::get);
        app.post("/api/sports-prediction-leagues", sportsPredictionLeaguesController::create);
        app.put("/api/sports-prediction-leagues/{id}", sportsPredictionLeaguesController::update);
        app.delete("/api/sports-prediction-leagues/{id}", sportsPredictionLeaguesController::delete);

        SportsPredictionFixturesController sportsPredictionFixturesController = new SportsPredictionFixturesController();
        app.get("/api/sports-prediction-fixtures", sportsPredictionFixturesController::getAll);
        app.get("/api/sports-prediction-fixtures/league/{leagueId}", sportsPredictionFixturesController::getForLeague);
        app.get("/api/sports-prediction-fixtures/{id}", sportsPredictionFixturesController::get);
        app.post("/api/sports-prediction-fixtures/sync-job", sportsPredictionFixturesController::syncJob);
        app.post("/api/sports-prediction-fixtures", sportsPredictionFixturesController::create);
        app.put("/api/sports-prediction-fixtures/{id}", sportsPredictionFixturesController::update);
        app.delete("/api/sports-prediction-fixtures/{id}", sportsPredictionFixturesController::delete);

        SportsPredictionLeagueParticipantsController sportsPredictionLeagueParticipantsController = new SportsPredictionLeagueParticipantsController();
        app.get("/api/sports-prediction-league-participants", sportsPredictionLeagueParticipantsController::getAll);
        app.get("/api/sports-prediction-league-participants/league/{leagueId}", sportsPredictionLeagueParticipantsController::getParticipantsForLeague);
        app.get("/api/sports-prediction-league-participants/{id}", sportsPredictionLeagueParticipantsController::get);
        app.post("/api/sports-prediction-league-participants", sportsPredictionLeagueParticipantsController::create);
        app.put("/api/sports-prediction-league-participants/{id}", sportsPredictionLeagueParticipantsController::update);
        app.delete("/api/sports-prediction-league-participants/{id}", sportsPredictionLeagueParticipantsController::delete);

        SportsPredictionPicksController sportsPredictionPicksController = new SportsPredictionPicksController();
        app.get("/api/sports-prediction-picks", sportsPredictionPicksController::getAll);
        app.get("/api/sports-prediction-picks/league/{leagueId}/recent", sportsPredictionPicksController::getRecentForLeague);
        app.get("/api/sports-prediction-picks/league/{leagueId}/mine", sportsPredictionPicksController::getMyPicksForLeague);
        app.get("/api/sports-prediction-picks/league/{leagueId}/fixture/{fixtureId}/mine", sportsPredictionPicksController::getMyPickForLeagueFixture);
        app.get("/api/sports-prediction-picks/league/{leagueId}/totals", sportsPredictionPicksController::getLeagueUserTotals);
        app.get("/api/sports-prediction-picks/{id}", sportsPredictionPicksController::get);
        app.post("/api/sports-prediction-picks/settle-job", sportsPredictionPicksController::settleJob);
        app.post("/api/sports-prediction-picks", sportsPredictionPicksController::create);
        app.put("/api/sports-prediction-picks/{id}", sportsPredictionPicksController::update);
        app.delete("/api/sports-prediction-picks/{id}", sportsPredictionPicksController::delete);

        WheelOptionsController wheelOptionsController = new WheelOptionsController();
        app.get("/api/wheel-options", wheelOptionsController::getAll);
        app.get("/api/wheel-options/{id}", wheelOptionsController::get);
        app.post("/api/wheel-options", wheelOptionsController::create);
        app.put("/api/wheel-options/{id}", wheelOptionsController::update);
        app.delete("/api/wheel-options/{id}", wheelOptionsController::delete);

        WheelSpinsController wheelSpinsController = new WheelSpinsController();
        app.get("/api/wheel-spins", wheelSpinsController::getAll);
        app.get("/api/wheel-spins/can-spin", wheelSpinsController::canSpinWheel);
        app.get("/api/wheel-spins/{id}", wheelSpinsController::get);
        app.post("/api/wheel-spins", wheelSpinsController::create);
        app.post("/api/wheel-spins/spin", wheelSpinsController::spinWheel);
        app.put("/api/wheel-spins/{id}", wheelSpinsController::update);
        app.delete("/api/wheel-spins/{id}", wheelSpinsController::delete);

        LoggingHelper.success("api started up on port " + port);
    }
}

record PingResponse(String message, int status) {}
