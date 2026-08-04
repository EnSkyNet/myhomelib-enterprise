package com.myhomelibcorp.application.event;

import com.myhomelibcorp.domain.model.collection.Collection;

import java.time.Instant;

/**
 * Подія, що виникає після відкриття колекції.
 * Використовується для ініціалізації компонентів, що залежать від активної колекції.
 */
public record CollectionOpenedEvent(Collection collection, Instant timestamp) {
    public CollectionOpenedEvent(Collection collection) {
        this(collection, Instant.now());
    }

    public String getCollectionName() {
        return collection != null ? collection.getName() : "unknown";
    }
}