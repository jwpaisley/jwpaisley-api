package com.jwpaisley.models;

import java.util.UUID;

public record Post(
    UUID id,
    String title,
    String description,
    String coverPhotoUrl,
    PostType type,
    String content,
    PostStatus status,
    Integer readingTime,
    Integer viewCount,
    String publishedAt,
    String archivedAt,
    String createdAt,
    String updatedAt
) {
    public enum PostType {
        SAILING,
        MOVIE_REVIEW,
        HIKES,
        WORLDBUILDING,
        WRITING;

        public String dbValue() {
            return switch (this) {
                case SAILING -> "sailing";
                case MOVIE_REVIEW -> "movie_review";
                case HIKES -> "hikes";
                case WORLDBUILDING -> "worldbuilding";
                case WRITING -> "writing";
            };
        }

        public static PostType fromDbValue(String value) {
            if (value == null) {
                return SAILING;
            }

            return switch (value.trim().toLowerCase()) {
                case "sailing" -> SAILING;
                case "movie_review" -> MOVIE_REVIEW;
                case "hikes" -> HIKES;
                case "worldbuilding" -> WORLDBUILDING;
                case "writing" -> WRITING;
                default -> throw new IllegalArgumentException("Unsupported post type: " + value);
            };
        }
    }

    public enum PostStatus {
        DRAFT,
        PUBLISHED,
        ARCHIVED;

        public String dbValue() {
            return switch (this) {
                case DRAFT -> "draft";
                case PUBLISHED -> "published";
                case ARCHIVED -> "archived";
            };
        }

        public static PostStatus fromDbValue(String value) {
            if (value == null) {
                return DRAFT;
            }

            return switch (value.trim().toLowerCase()) {
                case "draft" -> DRAFT;
                case "published" -> PUBLISHED;
                case "archived" -> ARCHIVED;
                default -> throw new IllegalArgumentException("Unsupported post status: " + value);
            };
        }
    }
}
