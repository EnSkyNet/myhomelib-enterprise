package com.myhomelibcorp.application.catalog;

/** Compact application projection used by the Followed Authors workspace. */
public record FollowedAuthorSummary(
        String authorId,
        String authorName,
        long activeBookCount,
        long newBookCount,
        String lastBookTitle,
        String lastBookDate,
        String followedAt
) {
    public FollowedAuthorSummary {
        authorId = authorId == null ? "" : authorId;
        authorName = authorName == null || authorName.isBlank() ? "Без автора" : authorName;
        lastBookTitle = lastBookTitle == null ? "" : lastBookTitle;
        lastBookDate = lastBookDate == null ? "" : lastBookDate;
        followedAt = followedAt == null ? "" : followedAt;
    }
}
