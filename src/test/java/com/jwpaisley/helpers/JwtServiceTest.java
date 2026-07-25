package com.jwpaisley.helpers;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtServiceTest {
    @Test
    void shouldCreateAndVerifyToken() throws Exception {
        JwtService jwtService = new JwtService("test-secret");
        String token = jwtService.createToken(Map.of("sub", "123", "email", "user@example.com"), Duration.ofMinutes(5));

        assertTrue(jwtService.isTokenValid(token));
        assertEquals("123", jwtService.getClaims(token).get("sub"));
        assertEquals("user@example.com", jwtService.getClaims(token).get("email"));
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        JwtService jwtService = new JwtService("test-secret");
        String token = jwtService.createToken(Map.of("sub", "123"), Duration.ofMillis(1));

        Thread.sleep(10);

        assertFalse(jwtService.isTokenValid(token));
    }
}
