package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.event.BookImportedEvent;
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
        eventBus.register(BookImportedEvent.class, this::handleBookImported);
        eventBus.register(BookDeletedEvent.class, this::handleBookDeleted);
        eventBus.register(BookUpdatedEvent.class, this::handleBookUpdated); // <-- додано
        log.info("BookEventHandler зареєстровано");
    }

    private void handleBookImported(BookImportedEvent event) {
        log.info("Отримано подію імпорту книги: {}", event.bookId());
        if (event.snapshot() != null) {
            searchIndexer.indexSnapshot(event.snapshot());
            log.info("Книгу проіндексовано: {}", event.bookId());
        }
    }

    private void handleBookDeleted(BookDeletedEvent event) {
        log.info("Отримано подію видалення книги: {}", event.getBookId());
        searchIndexer.deleteBook(event.getBookId());
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