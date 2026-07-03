package com.myhomelibcorp.ui.model.navigation;

import com.myhomelibcorp.domain.model.collection.Collection;

public record CollectionNode(Collection collection) implements LibraryNode {
    @Override
    public String toString() {
        return collection != null ? collection.getName() : "Колекція";
    }
}