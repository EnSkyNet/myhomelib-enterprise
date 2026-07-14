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

    public void loadExistingKeys() {
        if (cacheLoaded) {
            log.debug("Кеш дублікатів вже завантажено");
            return;
        }
        log.info("Завантаження існуючих ключів для перевірки дублікатів...");
        try {
            List<Book> allBooks = bookQueryRepository.findAll();
            // ОБЕРЕЖНО: findAll() може бути дуже важким для великих бібліотек (>1 млн книг)!
            // Для великих бібліотек потрібно використовувати інший підхід (наприклад, індекс у БД)
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

    private String buildNaturalKey(Book book) {
        String firstAuthor = book.getAuthors().stream()
                .findFirst()
                .map(Author::getLastName)
                .orElse("");
        return (book.getTitle() + "|" + firstAuthor).toLowerCase().trim();
    }

    public boolean isDuplicate(Book book) {
        if (!cacheLoaded) {
            loadExistingKeys();
        }
        String key = buildNaturalKey(book);
        boolean duplicate = existingKeys.contains(key);
        if (duplicate) {
            log.debug("Знайдено дублікат: '{}'", key);
        }
        return duplicate;
    }

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

    public void addKey(Book book) {
        if (book != null) {
            existingKeys.add(buildNaturalKey(book));
        }
    }

    public void addAllKeys(List<Book> books) {
        if (books != null) {
            for (Book book : books) {
                existingKeys.add(buildNaturalKey(book));
            }
        }
    }

    public void clearCache() {
        existingKeys.clear();
        cacheLoaded = false;
        log.debug("Кеш дублікатів очищено");
    }

    public boolean isCacheLoaded() {
        return cacheLoaded;
    }

    // Додатковий метод для оновлення кешу після імпорту
    public void refreshCache() {
        clearCache();
        loadExistingKeys();
    }
}