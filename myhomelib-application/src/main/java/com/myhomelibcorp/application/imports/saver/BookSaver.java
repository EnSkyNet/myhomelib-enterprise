package com.myhomelibcorp.application.imports.saver;

import com.myhomelibcorp.application.event.BookImportedEvent; // <-- ДОДАНО
import com.myhomelibcorp.application.imports.duplicate.DuplicateDetector;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import com.myhomelibcorp.domain.event.book.BookAddedEvent;

import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookSaver {

    private final BookCommandRepository bookCommandRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate transactionTemplate;
    private final DuplicateDetector duplicateDetector;
    private final SearchIndexer searchIndexer;

    /**
     * Зберігає одну книгу з індексацією (для одиночних операцій).
     * @return true, якщо книга була збережена
     */
    public boolean saveBook(Book book, boolean indexAfterSave, DuplicatePolicy policy) {
        if (book == null) {
            return false;
        }

        if (policy == DuplicatePolicy.SKIP && duplicateDetector.isDuplicate(book)) {
            log.debug("Дублікат пропущено: {}", book.getTitle());
            return false;
        }

        if (policy == DuplicatePolicy.REPLACE) {
            Optional<Book> existing = duplicateDetector.findDuplicate(book);
            if (existing.isPresent()) {
                log.debug("Дублікат замінено: {}", book.getTitle());
                transactionTemplate.execute(status -> {
                    bookCommandRepository.deleteById(existing.get().getId());
                    bookCommandRepository.save(book);
                    return null;
                });
                if (indexAfterSave) {
                    eventPublisher.publishEvent(new BookImportedEvent(BookSnapshot.fromBook(book)));
                }
                return true;
            }
        }

        if (policy == DuplicatePolicy.MERGE) {
            Optional<Book> existing = duplicateDetector.findDuplicate(book);
            if (existing.isPresent()) {
                log.debug("Дублікат об'єднано: {}", book.getTitle());
                Book merged = mergeBooks(existing.get(), book);
                transactionTemplate.execute(status -> {
                    bookCommandRepository.save(merged);
                    return null;
                });
                if (indexAfterSave) {
                    eventPublisher.publishEvent(new BookImportedEvent(BookSnapshot.fromBook(merged)));
                }
                return true;
            }
        }

        // SAVE_AS_NEW або звичайне збереження
        transactionTemplate.execute(status -> {
            bookCommandRepository.save(book);
            return null;
        });
        if (indexAfterSave) {
            eventPublisher.publishEvent(new BookImportedEvent(BookSnapshot.fromBook(book)));
            eventPublisher.publishEvent(new BookAddedEvent(BookSnapshot.fromBook(book)));
        }
        log.debug("Книгу збережено: {}", book.getTitle());
        return true;
    }

    /**
     * Зберігає батч книг з індексацією.
     * @return кількість збережених книг
     */
    public int saveBatch(List<Book> books, boolean indexAfterSave, DuplicatePolicy policy) {
        if (books == null || books.isEmpty()) {
            return 0;
        }

        // Відфільтровуємо дублікати згідно з політикою
        List<Book> booksToSave = books.stream()
                .filter(book -> {
                    if (policy == DuplicatePolicy.SKIP && duplicateDetector.isDuplicate(book)) {
                        log.debug("Дублікат пропущено (батч): {}", book.getTitle());
                        return false;
                    }
                    return true;
                })
                .toList();

        if (booksToSave.isEmpty()) {
            return 0;
        }

        // ---- Збереження в БД у транзакції ----
        transactionTemplate.execute(status -> {
            bookCommandRepository.saveBatch(booksToSave);
            return null;
        });

        // ---- Індексація батчем ----
        if (indexAfterSave) {
            searchIndexer.indexAll(booksToSave);
            // Після indexAll вже є commit, але для надійності викличемо ще раз
            searchIndexer.commit();
            // Публікуємо події для кожної книги
            for (Book book : booksToSave) {
                eventPublisher.publishEvent(new BookImportedEvent(BookSnapshot.fromBook(book)));
                eventPublisher.publishEvent(new BookAddedEvent(BookSnapshot.fromBook(book)));
            }
        }

        log.info("Збережено {} книг", booksToSave.size());
        return booksToSave.size();
    }

    /**
     * Об'єднує дві книги (при MERGE).
     */
    private Book mergeBooks(Book existing, Book incoming) {
        return Book.builder()
                .id(existing.getId())
                .title(incoming.getTitle())
                .authors(incoming.getAuthors())
                .genres(incoming.getGenres())
                .series(incoming.getSeries())
                .sequenceNumber(incoming.getSequenceNumber())
                .metadata(incoming.getMetadata())
                .file(incoming.getFile())
                .cover(existing.getCover())
                .updateDate(incoming.getUpdateDate())
                .createdAt(existing.getCreatedAt())
                .deleted(existing.isDeleted())
                .local(existing.isLocal())
                .build();
    }
}