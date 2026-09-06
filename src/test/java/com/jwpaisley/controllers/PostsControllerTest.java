package com.jwpaisley.controllers;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PostsControllerTest {
    @Test
    void shouldNotIncrementViewCountForAdmins() {
        assertFalse(PostsController.shouldIncrementViewCount(true));
    }

    @Test
    void shouldIncrementViewCountForNonAdmins() {
        assertTrue(PostsController.shouldIncrementViewCount(false));
    }
}
