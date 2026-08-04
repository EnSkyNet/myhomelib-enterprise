package com.myhomelibcorp.ui.model.navigation;

import com.myhomelibcorp.domain.model.publisher.Publisher;

public record PublisherNode(Publisher publisher) implements LibraryNode {
    @Override
    public String toString() {
        return publisher != null ? publisher.getName() : "Видавництво";
    }
}