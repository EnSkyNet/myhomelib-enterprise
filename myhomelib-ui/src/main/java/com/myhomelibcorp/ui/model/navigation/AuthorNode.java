package com.myhomelibcorp.ui.model.navigation;

import com.myhomelibcorp.domain.model.author.Author;

public record AuthorNode(Author author) implements LibraryNode {
    @Override
    public String toString() {
        return author != null ? author.getFullName() : "Автор";
    }
}