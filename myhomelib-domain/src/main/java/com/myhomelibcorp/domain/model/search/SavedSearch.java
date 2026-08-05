package com.myhomelibcorp.domain.model.search;

import lombok.Getter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
public class SavedSearch {
    private final String id;
    private final String name;
    private final String query;
    private final String filters; // JSON рядок з фільтрами
    private final LocalDateTime createdAt;
    private LocalDateTime lastUsed;
    private int useCount;

    public SavedSearch(String name, String query, String filters) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.query = query;
        this.filters = filters;
        this.createdAt = LocalDateTime.now();
        this.lastUsed = LocalDateTime.now();
        this.useCount = 0;
    }

    public SavedSearch withName(String newName) {
        SavedSearch copy = new SavedSearch(newName, this.query, this.filters);
        copy.useCount = this.useCount;
        copy.lastUsed = this.lastUsed;
        return copy;
    }

    public SavedSearch withUsage() {
        this.lastUsed = LocalDateTime.now();
        this.useCount++;
        return this;
    }

    @Override
    public String toString() {
        return name + " (" + useCount + ")";
    }
}