package com.jwpaisley.helpers;

import java.sql.Timestamp;
import java.time.Instant;

public class TimeHelper {
    private TimeHelper() {
    }

    public static String toUtcIsoString(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }

        return timestamp.toInstant().toString();
    }

    public static String toUtcIsoString(java.util.Date date) {
        if (date == null) {
            return null;
        }

        return date.toInstant().toString();
    }

    public static String toUtcIsoString(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
