package com.myhomelibcorp.infrastructure.event;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GenreService;
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
    private final GenreService genreService;

    @Async
    @EventListener
    public void handleBookImported(BookImportedEvent event) {
        log.info("Отримано подію імпорту книги: {}", event.getBookId());
        bookQueryRepository.findById(event.getBookId())
                .ifPresent(this::indexBook);
    }

    private void indexBook(Book book) {
        String genresText = book.getGenres().stream()
                .map(genre -> genreService.getGenreName(genre.getId().asString()))
                .collect(java.util.stream.Collectors.joining(", "));

        SearchDocument doc = SearchDocument.builder()
                .id(book.getId().asString())
                .title(book.getTitle() != null ? book.getTitle() : "")
                .authors(book.authorsText())
                .series(book.getSeries() != null ? book.getSeries() : "")
                .genres(genresText)
                .keywords(book.getMetadata().getKeywords() != null ? book.getMetadata().getKeywords() : "")
                .annotation(book.getMetadata().getAnnotation() != null ? book.getMetadata().getAnnotation() : "")
                .build();

        log.debug("Індексація книги: id={}, title={}, authors={}", doc.getId(), doc.getTitle(), doc.getAuthors());
        indexer.indexDocument(doc);
        log.info("Книгу проіндексовано: {}", book.getId().asString());
    }
}