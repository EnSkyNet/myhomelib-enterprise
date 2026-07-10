package com.myhomelibcorp.application.event;

import com.myhomelibcorp.domain.model.book.Book;

import java.util.List;

public record BooksImportedBatchEvent(List<Book> books) {
}