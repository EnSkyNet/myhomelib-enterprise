package com.myhomelibcorp.domain.model.navigation;

import com.myhomelibcorp.domain.model.book.Book;

public record BookNode(Book book) implements LibraryNode {
}