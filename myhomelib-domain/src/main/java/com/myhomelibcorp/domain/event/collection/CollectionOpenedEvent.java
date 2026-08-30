package com.myhomelibcorp.domain.event.collection;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.shared.event.BaseDomainEvent;


/**
 * Доменна подія, що виникає після відкриття колекції.
 * Використовується для ініціалізації компонентів, що залежать від активної колекції.
 */
public class CollectionOpenedEvent extends BaseDomainEvent {
    private final Collection collection;

    public CollectionOpenedEvent(Collection collection) {
        super("COLLECTION_OPENED");
        this.collection = collection;
    }

    public Collection getCollection() {
        return collection;
    }

    public String getCollectionId() {
        return collection != null ? collection.getId() : null;
    }

    public String getCollectionName() {
        return collection != null ? collection.getName() : "unknown";
    }
}