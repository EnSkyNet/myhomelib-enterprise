package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.List;

public interface BookCommandRepository {
    Book save(Book book);
    void saveBatch(List<Book> books); // Новий метод для batch-збереження
    void deleteById(BookId id);
    void updateRate(BookId bookId, int rate);
    void updateProgress(BookId bookId, int progress);
}