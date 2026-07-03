package com.myhomelibcorp.ui.model.navigation;

import com.myhomelibcorp.domain.model.genre.Genre;

public record GenreNode(Genre genre) implements LibraryNode {
    @Override
    public String toString() {
        return genre != null ? genre.getName() : "Жанр";
    }
}