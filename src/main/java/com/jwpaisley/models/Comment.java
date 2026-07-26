package com.jwpaisley.models;

import java.util.UUID;

public record Comment(
    UUID id,
    UUID user,
    UUID resource,
    CommentResourceType type,
    boolean isReply,
    UUID parentComment,
    String text,
    String createdAt,
    String updatedAt
) {}