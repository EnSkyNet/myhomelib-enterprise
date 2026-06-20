package com.myhomelibcorp.domain.model.series;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.UUID;

/**
 * Модель серії книг.
 */
@Getter
@RequiredArgsConstructor
public class Series {
    private final String id;
    private final String name;
    private final String description;

    public Series(String name) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.description = null;
    }

    public Series(String name, String description) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.description = description;
    }
}