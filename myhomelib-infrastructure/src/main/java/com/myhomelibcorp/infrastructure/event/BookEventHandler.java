package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.event.book.BookImportedEvent;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.infrastructure.search.LuceneSearchIndexer;
import com.myhomelibcorp.infrastructure.search.SearchDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class BookEventHandler {

    private final LuceneSearchIndexer indexer;
    private final BookQueryRepository bookQueryRepository;

    @Async
    @EventListener
    public void handleBookImported(BookImportedEvent event) {
        log.debug("Отримано подію імпорту книги: {}", event.getBookId());
        bookQueryRepository.findById(event.getBookId())
                .ifPresent(this::indexBook);
    }

    private void indexBook(Book book) {
        SearchDocument doc = SearchDocument.builder()
                .id(book.getId().asString())
                .title(book.getTitle() != null ? book.getTitle() : "")
                .authors(book.authorsText())
                .series(book.getSeries() != null ? book.getSeries() : "")
                .genres(book.genresText())
                .keywords(book.getKeywords() != null ? book.getKeywords() : "")
                .annotation(book.getAnnotation() != null ? book.getAnnotation() : "")
                .build();
        indexer.indexDocument(doc);
        log.debug("Книгу проіндексовано: {}", book.getId().asString());
    }
}