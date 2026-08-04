package com.myhomelibcorp.application.port.out.infrastructure;

import com.myhomelibcorp.domain.model.collection.Collection;

/**
 * Порт для переключення активної колекції.
 */
public interface CollectionSwitcher {
    void switchToCollection(Collection collection);
    Collection getCurrentCollection();
    void closeCurrentCollection();
    boolean hasActiveCollection();
}