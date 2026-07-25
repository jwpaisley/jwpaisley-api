package com.jwpaisley.helpers;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import io.javalin.http.Context;
import io.jsonwebtoken.Claims;
import java.util.Collections;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

public class AuthHelper {
    private static final String CLIENT_ID = "199559159303-u510108r3dv3oilmm8019bfihv8kp8lc.apps.googleusercontent.com";
    private static final Set<String> ADMIN_EMAILS = Set.of(
        "jacobpaisley97@gmail.com"
    );
    private static final JwtService JWT_SERVICE = new JwtService(
        System.getenv().getOrDefault("JWT_SECRET", "dummy-secret-for-dev")
    );

    private static final GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
            new NetHttpTransport(),
            new GsonFactory()
        )
        .setAudience(Collections.singletonList(CLIENT_ID))
        .build();

    public static boolean validateOAuthToken(Context ctx) {
        String authHeader = ctx.header("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            System.err.println("Missing or invalid Authorization header");
            return false;
        }

        String token = authHeader.substring(7).trim();

        if (JWT_SERVICE.isTokenValid(token)) {
            try {
                Claims claims = JWT_SERVICE.getClaims(token);
                ctx.attribute("userId", claims.get("userId", String.class));
                ctx.attribute("email", claims.get("email", String.class));
                ctx.attribute("firstName", claims.get("firstName", String.class));
                ctx.attribute("lastName", claims.get("lastName", String.class));
                ctx.attribute("isAdmin", claims.get("isAdmin", Boolean.class));
                return true;
            } catch (Exception e) {
                System.err.println("JWT parsing failed: " + e.getMessage());
                return false;
            }
        }

        try {
            GoogleIdToken idToken = verifier.verify(token);
            if (idToken != null) {
                Payload payload = idToken.getPayload();
                ctx.attribute("email", payload.getEmail());
                System.out.println("Authenticated user: " + payload.getEmail());
                return true;
            }
        } catch (Exception e) {
            System.err.println("Token verification failed: " + e.getMessage());
        }

        return false;
    }

    public static UUID getCurrentUserId(Context ctx) {
        Object userId = ctx.attribute("userId");
        if (userId instanceof String userIdString && !userIdString.isBlank()) {
            return UUID.fromString(userIdString);
        }
        return null;
    }

    public static String getCurrentUserEmail(Context ctx) {
        Object email = ctx.attribute("email");
        return email instanceof String emailString ? emailString : null;
    }

    public static boolean isAdmin(Context ctx) {
        Object adminFlag = ctx.attribute("isAdmin");
        if (Boolean.TRUE.equals(adminFlag)) {
            return true;
        }

        String email = getCurrentUserEmail(ctx);
        if (email == null) {
            System.err.println("Email verification failed: No email found in token payload");
            return false;
        }

        boolean isAllowedAdmin = ADMIN_EMAILS.contains(email.toLowerCase(Locale.ROOT));
        if (!isAllowedAdmin) {
            System.err.println("Unauthorized access attempt by: " + email);
        }

        return isAllowedAdmin;
    }
}