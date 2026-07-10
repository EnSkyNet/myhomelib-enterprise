package com.myhomelibcorp.application.imports.duplicate;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateDetector {

    private final BookQueryRepository bookQueryRepository;

    // Кеш натуральних ключів (title + first author last name)
    private Set<String> existingKeys = new HashSet<>();
    private boolean cacheLoaded = false;

    /**
     * Явне завантаження кешу дублікатів – викликати після вибору колекції.
     */
    public void loadExistingKeys() {
        if (cacheLoaded) {
            log.debug("Кеш дублікатів уже завантажено");
            return;
        }
        log.info("Завантаження існуючих ключів для перевірки дублікатів...");
        try {
            List<Book> allBooks = bookQueryRepository.findAll();
            existingKeys = allBooks.stream()
                    .map(this::buildNaturalKey)
                    .collect(Collectors.toSet());
            cacheLoaded = true;
            log.info("Завантажено {} ключів", existingKeys.size());
        } catch (Exception e) {
            log.error("Не вдалося завантажити ключі дублікатів", e);
            existingKeys = new HashSet<>();
            cacheLoaded = false;
        }
    }

    /**
     * Будує натуральний ключ для книги.
     */
    private String buildNaturalKey(Book book) {
        String firstAuthor = book.getAuthors().stream()
                .findFirst()
                .map(Author::getLastName)
                .orElse("");
        return book.getTitle() + "|" + firstAuthor;
    }

    /**
     * Перевіряє, чи є книга дублікатом (O(1) завдяки кешу).
     */
    public boolean isDuplicate(Book book) {
        if (!cacheLoaded) {
            // Якщо кеш не завантажено – завантажуємо на льоту (але краще викликати loadExistingKeys заздалегідь)
            loadExistingKeys();
        }
        return existingKeys.contains(buildNaturalKey(book));
    }

    /**
     * Знаходить дублікат у БД (якщо потрібен повний об'єкт).
     * Використовується рідко, переважно для політики REPLACE/MERGE.
     */
    public Optional<Book> findDuplicate(Book book) {
        if (!cacheLoaded) {
            loadExistingKeys();
        }
        String key = buildNaturalKey(book);
        if (existingKeys.contains(key)) {
            String firstAuthor = book.getAuthors().stream()
                    .findFirst()
                    .map(Author::getLastName)
                    .orElse("");
            return bookQueryRepository.findByTitleAndAuthor(book.getTitle(), firstAuthor);
        }
        return Optional.empty();
    }

    /**
     * Додає ключ нової книги до кешу (після збереження).
     */
    public void addKey(Book book) {
        if (book != null) {
            existingKeys.add(buildNaturalKey(book));
        }
    }

    /**
     * Додає ключі батча книг до кешу.
     */
    public void addAllKeys(List<Book> books) {
        if (books != null) {
            for (Book book : books) {
                existingKeys.add(buildNaturalKey(book));
            }
        }
    }

    /**
     * Очищує кеш (наприклад, при зміні колекції).
     */
    public void clearCache() {
        existingKeys.clear();
        cacheLoaded = false;
        log.debug("Кеш дублікатів очищено");
    }

    /**
     * Перевіряє, чи кеш завантажено.
     */
    public boolean isCacheLoaded() {
        return cacheLoaded;
    }
}