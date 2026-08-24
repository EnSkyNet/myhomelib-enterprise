package com.myhomelibcorp.application.navigation;

import java.util.Arrays;

/** Stable identifiers for Stage 4 review/rating navigation subsets. */
public enum ReviewNavigationFilter {
    RATED("rated", true, false),
    REVIEWED("reviewed", false, true),
    RATED_AND_REVIEWED("rated-reviewed", true, true);

    private final String id;
    private final boolean onlyRated;
    private final boolean onlyReviewed;

    ReviewNavigationFilter(String id, boolean onlyRated, boolean onlyReviewed) {
        this.id = id;
        this.onlyRated = onlyRated;
        this.onlyReviewed = onlyReviewed;
    }

    public String id() { return id; }
    public boolean onlyRated() { return onlyRated; }
    public boolean onlyReviewed() { return onlyReviewed; }

    public static ReviewNavigationFilter fromId(String id) {
        return Arrays.stream(values())
                .filter(value -> value.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown review navigation filter: " + id));
    }
}
