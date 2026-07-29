package com.jwpaisley.helpers;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JwtHelperTest {
    @Test
    void shouldCreateAndVerifyToken() throws Exception {
        JwtHelper jwtHelper = new JwtHelper("test-secret");
        String token = jwtHelper.createToken(Map.of("sub", "123", "email", "user@example.com"), Duration.ofMinutes(5));

        assertTrue(jwtHelper.isTokenValid(token));
        assertEquals("123", jwtHelper.getClaims(token).get("sub"));
        assertEquals("user@example.com", jwtHelper.getClaims(token).get("email"));
    }

    @Test
    void shouldRejectExpiredToken() throws Exception {
        JwtHelper jwtHelper = new JwtHelper("test-secret");
        String token = jwtHelper.createToken(Map.of("sub", "123"), Duration.ofMillis(1));

        Thread.sleep(10);

        assertFalse(jwtHelper.isTokenValid(token));
    }
}
