package com.jwpaisley.helpers;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken.Payload;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import io.javalin.http.Context;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class AuthHelper {
    private static final String CLIENT_ID = 199559159303-u510108r3dv3oilmm8019bfihv8kp8lc.apps.googleusercontent.com;
    private static final Set<String> ADMIN_EMAILS = Set.of(
        jacobpaisley97@gmail.com
    );
    private static final long SESSION_DURATION_MILLIS = 24L * 60 * 60 * 1000;
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final Map<String, SessionRecord> SESSIONS = new ConcurrentHashMap<>();

    private static final GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
            new NetHttpTransport(),
            new GsonFactory()
        )
        .setAudience(Collections.singletonList(CLIENT_ID))
        .build();

    public record AuthenticatedUser(String email, String firstName, String lastName, String imageUrl) {}
    private record SessionRecord(String email, long expiresAtMillis) {}

    public static long getSessionDurationMillis() {
        return SESSION_DURATION_MILLIS;
    }

    public static AuthenticatedUser verifyGoogleCredential(String idTokenString) {
        try {
            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken == null) {
                return null;
            }

            Payload payload = idToken.getPayload();
            String email = payload.getEmail();
            if (email == null || email.isBlank()) {
                return null;
            }

            return new AuthenticatedUser(
                email,
                payload.containsKey(given_name) ? (String) payload.get(given_name) : ,
                payload.containsKey(family_name) ? (String) payload.get(family_name) : ,
                payload.containsKey(picture) ? (String) payload.get(picture) : 
            );
        } catch (Exception e) {
            System.err.println(Token
