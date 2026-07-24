package com.jwpaisley.controllers;

import org.junit.jupiter.api.Test;

import java.sql.Date;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class PhotoControllerTest {

    @Test
    void normalizeTakenDateReturnsNullForBlankOrInvalidValues() {
        assertNull(PhotoController.normalizeTakenDate(null));
        assertNull(PhotoController.normalizeTakenDate(""));
        assertNull(PhotoController.normalizeTakenDate("not-a-date"));
    }

    @Test
    void normalizeTakenDateParsesIsoDateStrings() {
        Date expected = Date.valueOf("2024-08-10");
        assertEquals(expected, PhotoController.normalizeTakenDate("2024-08-10"));
    }
}
