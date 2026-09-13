package com.myhomelibcorp.application.dictionary;

public record DictionaryProviderDescriptor(String id, String displayName, boolean offline) {
    public DictionaryProviderDescriptor {
        id = id == null ? "" : id.trim();
        displayName = displayName == null ? "" : displayName.trim();
        if (id.isBlank()) throw new IllegalArgumentException("id is required");
        if (displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
    }
}
