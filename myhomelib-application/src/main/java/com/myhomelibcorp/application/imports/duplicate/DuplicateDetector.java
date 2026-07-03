package com.myhomelibcorp.application.imports.duplicate;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateDetector {

    private final BookQueryRepository bookQueryRepository;

    /**
     * Перевіряє, чи існує книга з такою ж назвою та автором.
     * @param book книга для перевірки
     * @return Optional з існуючою книгою, якщо знайдено
     */
    public Optional<Book> findDuplicate(Book book) {
        if (book == null || book.getTitle() == null || book.getTitle().isBlank()) {
            return Optional.empty();
        }

        // Спроба знайти за назвою та прізвищем першого автора
        String firstAuthorLastName = book.getAuthors().stream()
                .findFirst()
                .map(author -> author.getLastName())
                .orElse("");

        if (firstAuthorLastName.isEmpty()) {
            return Optional.empty();
        }

        return bookQueryRepository.findByTitleAndAuthor(book.getTitle(), firstAuthorLastName);
    }

    /**
     * Перевіряє, чи є книга дублікатом.
     */
    public boolean isDuplicate(Book book) {
        return findDuplicate(book).isPresent();
    }

    /**
     * Знаходить дублікат або повертає порожній Optional.
     */
    public Optional<Book> findDuplicateOrEmpty(Book book) {
        return findDuplicate(book);
    }
}