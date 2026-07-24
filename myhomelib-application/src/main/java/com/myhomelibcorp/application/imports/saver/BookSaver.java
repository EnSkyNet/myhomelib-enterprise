package com.myhomelibcorp.application.imports.saver;

import com.myhomelibcorp.application.event.BooksImportedBatchEvent;
import com.myhomelibcorp.application.imports.duplicate.DuplicateDetector;
import com.myhomelibcorp.application.imports.duplicate.DuplicatePolicy;
import com.myhomelibcorp.application.port.out.repository.BookCommandRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookSaver {

    private final BookCommandRepository bookCommandRepository;
    private final BookQueryRepository bookQueryRepository; // <-- Додано
    private final ApplicationEventPublisher eventPublisher; // <-- Додано
    private final DuplicateDetector duplicateDetector;
    private final SearchIndexer searchIndexer;

    @Qualifier("collectionTransactionTemplate")
    private final TransactionTemplate transactionTemplate;

    public boolean saveBook(Book book, boolean indexAfterSave, DuplicatePolicy policy) {
        if (book == null) return false;

        if (policy == DuplicatePolicy.SKIP && duplicateDetector.isDuplicate(book)) {
            log.debug("Дублікат пропущено: {}", book.getTitle());
            return false;
        }

        if (policy == DuplicatePolicy.REPLACE) {
            var existing = duplicateDetector.findDuplicate(book);
            if (existing.isPresent()) {
                transactionTemplate.execute(status -> {
                    bookCommandRepository.deleteById(existing.get().getId());
                    bookCommandRepository.save(book);
                    return null;
                });
                duplicateDetector.addKey(book);
                if (indexAfterSave) {
                    searchIndexer.indexBook(book);
                    searchIndexer.commit();
                }
                eventPublisher.publishEvent(new BooksImportedBatchEvent(List.of(book)));
                return true;
            }
        }

        if (policy == DuplicatePolicy.MERGE) {
            var existing = duplicateDetector.findDuplicate(book);
            if (existing.isPresent()) {
                Book merged = mergeBooks(existing.get(), book);
                transactionTemplate.execute(status -> {
                    bookCommandRepository.save(merged);
                    return null;
                });
                duplicateDetector.addKey(merged);
                if (indexAfterSave) {
                    searchIndexer.indexBook(merged);
                    searchIndexer.commit();
                }
                eventPublisher.publishEvent(new BooksImportedBatchEvent(List.of(merged)));
                return true;
            }
        }

        transactionTemplate.execute(status -> {
            bookCommandRepository.save(book);
            return null;
        });
        duplicateDetector.addKey(book);
        if (indexAfterSave) {
            searchIndexer.indexBook(book);
            searchIndexer.commit();
        }
        eventPublisher.publishEvent(new BooksImportedBatchEvent(List.of(book)));
        log.debug("Книгу збережено: {}", book.getTitle());
        return true;
    }

    public int saveBatch(List<Book> books, boolean indexAfterSave, DuplicatePolicy policy) {
        if (books == null || books.isEmpty()) return 0;

        List<Book> booksToSave;

        if (policy == DuplicatePolicy.SKIP) {
            booksToSave = new ArrayList<>();
            for (Book book : books) {
                if (!duplicateDetector.isDuplicate(book)) {
                    booksToSave.add(book);
                } else {
                    log.debug("Дублікат пропущено (батч): {}", book.getTitle());
                }
            }
        } else {
            booksToSave = books;
        }

        if (booksToSave.isEmpty()) {
            log.debug("Батч не містить нових книг");
            return 0;
        }

        transactionTemplate.execute(status -> {
            bookCommandRepository.saveBatch(booksToSave);
            return null;
        });

        duplicateDetector.addAllKeys(booksToSave);

        if (indexAfterSave) {
            searchIndexer.indexAll(booksToSave);
            searchIndexer.commit();
        }

        eventPublisher.publishEvent(new BooksImportedBatchEvent(booksToSave));

        log.info("Збережено {} книг (батч)", booksToSave.size());
        return booksToSave.size();
    }

    /**
     * Видаляє книгу за ID та публікує подію BookDeletedEvent.
     */
    public void deleteBook(BookId bookId) {
        if (bookId == null) {
            throw new IllegalArgumentException("BookId cannot be null");
        }

        Optional<Book> bookOpt = bookQueryRepository.findById(bookId);
        if (bookOpt.isEmpty()) {
            log.warn("Спроба видалити неіснуючу книгу: {}", bookId);
            return;
        }

        transactionTemplate.execute(status -> {
            bookCommandRepository.deleteById(bookId);
            return null;
        });

        // Публікуємо подію видалення
        eventPublisher.publishEvent(new BookDeletedEvent(bookId));
        log.debug("Книгу видалено: {}", bookId);
    }

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