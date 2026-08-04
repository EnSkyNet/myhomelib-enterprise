package com.myhomelibcorp.infrastructure.adapter;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionSwitcher;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class CollectionSwitcherAdapter implements CollectionSwitcher {

    private final CollectionManager collectionManager;

    @Override
    public void switchToCollection(Collection collection) {
        collectionManager.switchToCollection(collection);
    }

    @Override
    public Collection getCurrentCollection() {
        return collectionManager.getCurrentCollection();
    }

    @Override
    public void closeCurrentCollection() {
        collectionManager.closeCurrentCollection();
    }

    @Override
    public boolean hasActiveCollection() {
        return collectionManager.hasActiveCollection();
    }
}