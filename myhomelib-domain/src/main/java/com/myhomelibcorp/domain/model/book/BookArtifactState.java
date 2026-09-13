package com.myhomelibcorp.domain.model.book;

/**
 * Availability/health state of one physical or remote representation of a logical book.
 */
public enum BookArtifactState {
    AVAILABLE,
    REMOTE_ONLY,
    MISSING,
    CORRUPT,
    UNAVAILABLE;

    public static BookArtifactState fromStorage(String value, boolean local, boolean remote) {
        if (value != null && !value.isBlank()) {
            try {
                return BookArtifactState.valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException ignored) {
                // Fall through to legacy flags for backward compatibility.
            }
        }
        if (local) return AVAILABLE;
        if (remote) return REMOTE_ONLY;
        return MISSING;
    }
}
