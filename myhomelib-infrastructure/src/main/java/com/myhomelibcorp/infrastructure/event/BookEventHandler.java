package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.event.BooksImportedBatchEvent;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.event.book.BookDeletedEvent;
import com.myhomelibcorp.domain.event.book.BookUpdatedEvent;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookEventHandler {

    private final SimpleEventBus eventBus;
    private final SearchIndexer searchIndexer;

    @PostConstruct
    public void init() {
        eventBus.register(BooksImportedBatchEvent.class, this::handleBooksImportedBatch);
        eventBus.register(BookDeletedEvent.class, this::handleBookDeleted);
        eventBus.register(BookUpdatedEvent.class, this::handleBookUpdated);
        log.info("BookEventHandler зареєстровано для батчевих подій, видалення та оновлення");
    }

    private void handleBooksImportedBatch(BooksImportedBatchEvent event) {
        int size = event.books().size();
        log.info("Отримано батч імпорту з {} книг. Індексація вже виконана в BookSaver.", size);
        // Індексація вже відбувається в BookSaver, тому тут нічого не робимо
    }

    private void handleBookDeleted(BookDeletedEvent event) {
        log.info("Отримано подію видалення книги: {}", event.getBookId());
        searchIndexer.deleteBook(event.getBookId());
        log.info("Книгу видалено з індексу: {}", event.getBookId());
    }

    private void handleBookUpdated(BookUpdatedEvent event) {
        log.info("Отримано подію оновлення книги: {}", event.getBookId());
        if (event.getBookSnapshot() != null) {
            searchIndexer.indexSnapshot(event.getBookSnapshot());
            log.info("Індекс оновлено для книги: {}", event.getBookId());
        } else {
            log.warn("Snapshot відсутній для оновлення книги: {}", event.getBookId());
        }
    }
}