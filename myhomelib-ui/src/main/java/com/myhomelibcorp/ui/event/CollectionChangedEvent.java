package com.myhomelibcorp.ui.event;

import com.myhomelibcorp.domain.model.collection.Collection;

public record CollectionChangedEvent(Collection collection) {
}