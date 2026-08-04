package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.valueobject.BookId;

import java.util.Optional;

/**
 * Порт для перевірки дублікатів книг.
 * Використовується в DuplicateDetector для пошуку дублікатів за назвою та автором.
 */
public interface DuplicateBookLookup {

    /**
     * Шукає ID книги, яка є дублікатом за назвою та прізвищем першого автора.
     * @param title назва книги
     * @param firstAuthorLastName прізвище першого автора
     * @return Optional з ID книги, якщо дублікат знайдено
     */
    Optional<BookId> findDuplicateId(String title, String firstAuthorLastName);

    /**
     * Перевіряє, чи існує дублікат за назвою та прізвищем першого автора.
     * @param title назва книги
     * @param firstAuthorLastName прізвище першого автора
     * @return true, якщо дублікат існує
     */
    boolean existsDuplicate(String title, String firstAuthorLastName);
}