package com.jwpaisley.models;

import java.util.Date;
import java.util.List;
import java.util.UUID;

public record Book(
    UUID id,
    String title,
    String author,
    String coverImage,
    String description,
    String state,

    int pageCount,
    int currentPage,
    int rating,
    String review,
    String startDate,
    String finishDate,

    String createdAt,
    String updatedAt
) {}