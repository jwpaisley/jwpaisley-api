package com.jwpaisley.helpers;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import io.javalin.http.Context;
import java.util.Collections;
import java.util.Set;

public class AuthHelper {
    private static final String CLIENT_ID = "199559159303-u510108r3dv3oilmm8019bfihv8kp8lc.apps.googleusercontent.com";
    private static final Set<String> ADMIN_EMAILS = Set.of(
        "jacobpaisley97@gmail.com"
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
            System.out.println(authHeader);
            System.err.println("Missing or invalid Authorization header");
            return false;
        }

        String idTokenString = authHeader.substring(7);

        try {
            GoogleIdToken idToken = verifier.verify(idTokenString);
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

    public static boolean isAdmin(Context ctx) {
        String email = ctx.attribute("email");
        
        if (email == null) {
            System.err.println("Email verification failed: No email found in token payload");
            return false;
        }

        boolean isAdmin = ADMIN_EMAILS.contains(email.toLowerCase());
        if (!isAdmin) {
            System.err.println("Unauthorized access attempt by: " + email);
        }

        return isAdmin;
    }
}