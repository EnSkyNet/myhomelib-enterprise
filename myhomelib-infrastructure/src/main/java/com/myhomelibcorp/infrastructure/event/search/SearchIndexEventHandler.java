package com.myhomelibcorp.infrastructure.event.search;

import com.myhomelibcorp.application.event.BookImportedEvent;
import com.myhomelibcorp.application.port.out.search.SearchIndexer;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.infrastructure.event.SimpleEventBus;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SearchIndexEventHandler {

    private final SimpleEventBus eventBus;
    private final SearchIndexer searchIndexer;

    @PostConstruct
    public void init() {
        eventBus.register(BookImportedEvent.class, this::handleBookImported);
        log.info("SearchIndexEventHandler зареєстровано");
    }

    private void handleBookImported(BookImportedEvent event) {
        // Використовуємо методи record: bookId() та snapshot()
        log.info("Отримано подію імпорту книги: {}", event.bookId());
        BookSnapshot snapshot = event.snapshot();
        if (snapshot != null) {
            searchIndexer.indexSnapshot(snapshot);
            log.info("Книгу проіндексовано: {}", event.bookId());
        } else {
            log.warn("Snapshot відсутній для книги: {}", event.bookId());
        }
    }
}