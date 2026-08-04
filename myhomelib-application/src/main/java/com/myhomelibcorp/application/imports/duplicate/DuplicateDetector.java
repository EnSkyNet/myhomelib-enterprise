package com.myhomelibcorp.application.imports.duplicate;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.DuplicateBookLookup;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateDetector {

    private final DuplicateBookLookup duplicateBookLookup;
    private final BookQueryRepository bookQueryRepository;

    private final Set<String> batchKeyCache = new HashSet<>();
    private static final int MAX_CACHE_SIZE = 10_000;

    public boolean isDuplicate(Book book) {
        if (book == null || book.getAuthors().isEmpty()) {
            return false;
        }

        String key = buildNaturalKey(book);
        if (batchKeyCache.contains(key)) {
            return true;
        }

        String title = book.getTitle();
        String firstAuthorLastName = book.getAuthors().get(0).getLastName();

        try {
            boolean exists = duplicateBookLookup.existsDuplicate(title, firstAuthorLastName);
            if (exists) {
                batchKeyCache.add(key);
            }
            return exists;
        } catch (Exception e) {
            log.error("Помилка перевірки дубліката для книги: {}", title, e);
            return false;
        }
    }

    public Optional<Book> findDuplicate(Book book) {
        if (book == null || book.getAuthors().isEmpty()) {
            return Optional.empty();
        }

        String title = book.getTitle();
        String firstAuthorLastName = book.getAuthors().get(0).getLastName();

        try {
            Optional<BookId> bookId = duplicateBookLookup.findDuplicateId(title, firstAuthorLastName);
            if (bookId.isPresent()) {
                return bookQueryRepository.findById(bookId.get());
            }
        } catch (Exception e) {
            log.debug("Дублікат не знайдено для книги: {}", title, e);
        }
        return Optional.empty();
    }

    public void addKey(Book book) {
        if (book == null) return;
        String key = buildNaturalKey(book);
        batchKeyCache.add(key);

        if (batchKeyCache.size() > MAX_CACHE_SIZE) {
            log.debug("Кеш дублікатів перевищив {} записів, очищення", MAX_CACHE_SIZE);
            batchKeyCache.clear();
        }
    }

    public void addAllKeys(List<Book> books) {
        if (books == null || books.isEmpty()) return;
        for (Book book : books) {
            batchKeyCache.add(buildNaturalKey(book));
        }
        if (batchKeyCache.size() > MAX_CACHE_SIZE) {
            batchKeyCache.clear();
        }
    }

    public void clearCache() {
        batchKeyCache.clear();
        log.debug("Кеш дублікатів очищено");
    }

    private String buildNaturalKey(Book book) {
        String firstAuthor = book.getAuthors().stream()
                .findFirst()
                .map(Author::getLastName)
                .orElse("");
        return (book.getTitle() + "|" + firstAuthor).toLowerCase().trim();
    }
}