package com.myhomelibcorp.application.translation;

public record TranslationProviderDescriptor(String id, String displayName, boolean remote) {
    public TranslationProviderDescriptor {
        id = id == null ? "" : id.trim();
        displayName = displayName == null ? "" : displayName.trim();
        if (id.isBlank()) throw new IllegalArgumentException("id is required");
        if (displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
    }
}
