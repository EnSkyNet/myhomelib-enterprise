package com.myhomelibcorp.ui.model.navigation;

import com.myhomelibcorp.domain.model.book.Book;

public record BookNode(Book book) implements LibraryNode {
}