package com.myhomelibcorp.application.context;

import com.myhomelibcorp.domain.model.collection.Collection;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class CollectionContext {

    private final AtomicReference<Collection> currentCollection = new AtomicReference<>();

    public Collection getCurrentCollection() {
        return currentCollection.get();
    }

    public void setCurrentCollection(Collection collection) {
        currentCollection.set(collection);
    }

    public boolean hasCollection() {
        return currentCollection.get() != null;
    }

    public String getCurrentCollectionId() {
        Collection c = currentCollection.get();
        return c != null ? c.getId() : null;
    }
}