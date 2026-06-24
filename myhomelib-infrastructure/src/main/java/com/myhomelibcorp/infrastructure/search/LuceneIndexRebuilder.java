package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.application.port.out.GenreService;
import com.myhomelibcorp.application.port.out.IndexRebuilder;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class LuceneIndexRebuilder implements IndexRebuilder {

    private final LuceneSearchIndexer indexer;
    private final BookQueryRepository bookQueryRepository;
    private final GenreService genreService;

    @Override
    public void rebuildIndex() {
        log.info("Початок перебудови Lucene індексу...");
        indexer.rebuildIndex();

        List<Book> allBooks = bookQueryRepository.findAll(Integer.MAX_VALUE, 0);
        log.info("Завантажено {} книг для індексації", allBooks.size());

        int indexed = 0;
        for (Book book : allBooks) {
            try {
                String genresText = book.getGenres().stream()
                        .map(genre -> genreService.getGenreName(genre.getId().asString()))
                        .collect(Collectors.joining(", "));

                SearchDocument doc = SearchDocument.builder()
                        .id(book.getId().asString())
                        .title(book.getTitle() != null ? book.getTitle() : "")
                        .authors(book.authorsText())
                        .series(book.getSeries() != null ? book.getSeries() : "")
                        .genres(genresText)
                        .keywords(book.getKeywords() != null ? book.getKeywords() : "")
                        .annotation(book.getAnnotation() != null ? book.getAnnotation() : "")
                        .build();

                indexer.indexDocument(doc);
                indexed++;
                if (indexed % 1000 == 0) {
                    log.debug("Проіндексовано {} книг", indexed);
                }
            } catch (Exception e) {
                log.error("Помилка індексації книги id={}", book.getId().asString(), e);
            }
        }

        log.info("Перебудова індексу завершена. Проіндексовано {} книг", indexed);
    }

    @Override
    public int getIndexedDocumentCount() {
        return indexer.getDocumentCount();
    }
}