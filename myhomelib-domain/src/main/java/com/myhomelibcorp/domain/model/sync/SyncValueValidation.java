package com.myhomelibcorp.domain.model.sync;

final class SyncValueValidation {
    private SyncValueValidation() { }

    static String requiredText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        String normalized = value.trim();
        if (normalized.length() > 512) throw new IllegalArgumentException(field + " is too long");
        if (normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(field + " contains control characters");
        }
        return normalized;
    }
}
