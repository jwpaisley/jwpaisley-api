package com.jwpaisley.helpers;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

public class CacheHelper {
    private final Map<CacheKey, CacheEntry<?>> entries = new ConcurrentHashMap<>();

    public <T> T get(CacheType cacheType, String key, Supplier<T> supplier) {
        CacheKey cacheKey = new CacheKey(cacheType, key);
        CacheEntry<?> existing = entries.get(cacheKey);
        Instant now = Instant.now();
        if (existing != null && !existing.isExpired(now, cacheType.ttl())) {
            LoggingHelper.debug("cache hit for <" + key + "> in the " + cacheType.displayName() + " cache");
            return (T) existing.value();
        }

        LoggingHelper.debug("cache miss for <" + key + "> in the " + cacheType.displayName() + " cache");
        return (T) entries.compute(cacheKey, (ignored, current) -> {
            if (current != null && !current.isExpired(now, cacheType.ttl())) {
                LoggingHelper.debug("cache hit for <" + key + "> in the " + cacheType.displayName() + " cache");
                return current;
            }

            Instant createdAt = Instant.now();
            T cachedValue = supplier.get();
            LoggingHelper.info(
                "cached data for <" + key + "> in the " + cacheType.displayName() + " cache, expires at "
                    + formatExpiryTime(createdAt.plus(cacheType.ttl()))
            );
            return new CacheEntry<>(cachedValue, createdAt);
        }).value();
    }

    public <T> void put(CacheType cacheType, String key, T value) {
        Instant createdAt = Instant.now();
        entries.put(new CacheKey(cacheType, key), new CacheEntry<>(value, createdAt));
        LoggingHelper.info(
            "cached data for <" + key + "> in the " + cacheType.displayName() + " cache, expires at "
                + formatExpiryTime(createdAt.plus(cacheType.ttl()))
        );
    }

    public void invalidate(CacheType cacheType, String key) {
        entries.remove(new CacheKey(cacheType, key));
        LoggingHelper.info("invalidated cached data for <" + key + "> in the " + cacheType.displayName() + " cache");
    }

    public enum CacheType {
        SAILING_CONDITIONS_CACHE(Duration.ofMinutes(15));

        private final Duration ttl;

        CacheType(Duration ttl) {
            this.ttl = ttl;
        }

        public Duration ttl() {
            return ttl;
        }

        public String displayName() {
            return switch (this) {
                case SAILING_CONDITIONS_CACHE -> "sailing conditions";
            };
        }
    }

    private static String formatExpiryTime(Instant expiryTime) {
        LocalDateTime localDateTime = LocalDateTime.ofInstant(expiryTime, ZoneId.systemDefault());
        String month = localDateTime.getMonth().getDisplayName(TextStyle.FULL, Locale.US).toLowerCase(Locale.US);
        int day = localDateTime.getDayOfMonth();
        String ordinal = ordinalSuffix(day);
        String time = localDateTime.format(DateTimeFormatter.ofPattern("h:mma", Locale.US)).toUpperCase(Locale.US);
        int year = localDateTime.getYear();
        return time + " on " + month + " " + day + ordinal + ", " + year;
    }

    private static String ordinalSuffix(int day) {
        if (day >= 11 && day <= 13) {
            return "th";
        }

        return switch (day % 10) {
            case 1 -> "st";
            case 2 -> "nd";
            case 3 -> "rd";
            default -> "th";
        };
    }

    private record CacheKey(CacheType cacheType, String key) {
    }

    private record CacheEntry<T>(T value, Instant createdAt) {
        boolean isExpired(Instant now, Duration ttl) {
            return createdAt.plus(ttl).isBefore(now);
        }
    }
}
