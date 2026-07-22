package com.jwpaisley.controllers;

import com.jwpaisley.helpers.AuthHelper;
import io.javalin.http.Context;

import java.util.Map;

public class AuthController {
    public void googleLogin(Context ctx) {
        try {
            Map<String, Object> body = ctx.bodyAsClass(Map.class);
            Object credentialObject = body.get("credential");

            if (!(credentialObject instanceof String credential) || credential.isBlank()) {
                ctx.status(400).json(Map.of("error", "Missing Google credential"));
                return;
            }

            AuthHelper.AuthenticatedUser authenticatedUser = AuthHelper.verifyGoogleCredential(credential);
            if (authenticatedUser == null) {
                ctx.status(401).json(Map.of("error", "Invalid Google credential"));
                return;
            }

            String sessionToken = AuthHelper.issueSessionToken(authenticatedUser.email());
            long expiresAt = System.currentTimeMillis() + AuthHelper.getSessionDurationMillis();

            ctx.json(Map.of(
                "token", sessionToken,
                "expiresAt", expiresAt,
                "user", Map.of(
                    "email", authenticatedUser.email(),
                    "firstName", authenticatedUser.firstName(),
                    "lastName", authenticatedUser.lastName(),
                    "imageUrl", authenticatedUser.imageUrl()
                )
            ));
        } catch (Exception e) {
            System.err.println("Google auth failed: " + e.getMessage());
            ctx.status(400).json(Map.of("error", "Unable to process Google login"));
        }
    }
}
